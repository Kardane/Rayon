package dev.lazurite.rayon.impl.bullet.collision.space.cache;

import dev.lazurite.rayon.impl.Rayon;
import dev.lazurite.rayon.impl.bullet.collision.body.ElementRigidBody;
import dev.lazurite.rayon.impl.bullet.collision.space.MinecraftSpace;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * 서버 스레드에서 섹션 수요와 블록 snapshot을 관리한다.
 * 물리 스레드에는 불변 {@link TerrainSectionSnapshot}만 전달한다.
 */
public final class TerrainSectionCache {
    private static final int DEFAULT_SNAPSHOT_SECTIONS_PER_TICK = 2;
    private static final long DEFAULT_MAX_SNAPSHOT_NANOS_PER_TICK = 2_000_000L;
    private static final long DEFAULT_SECTION_GRACE_TICKS = 60L;
    private static final long METRICS_INTERVAL_TICKS = 100L;

    private final MinecraftSpace space;
    private final Long2ObjectMap<TerrainSectionEntry> entries = new Long2ObjectOpenHashMap<>();
    private final Map<Long, TerrainSectionSnapshot> publishedSnapshots = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<TerrainSectionSnapshot> pendingSnapshots = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<AppliedRevision> appliedRevisions = new ConcurrentLinkedQueue<>();
    private final Map<Long, Integer> currentRevisions = new HashMap<>();
    private final Object revisionLock = new Object();
    private final Metrics metrics = new Metrics();
    private final int snapshotSectionsPerTick;
    private final long maxSnapshotNanosPerTick;
    private final long sectionGraceTicks;
    private long lastMetricsTick = Long.MIN_VALUE;

    public TerrainSectionCache(MinecraftSpace space) {
        this.space = space;
        this.snapshotSectionsPerTick = positiveIntProperty(
                "rayon.terrain.snapshotSectionsPerTick", DEFAULT_SNAPSHOT_SECTIONS_PER_TICK);
        this.maxSnapshotNanosPerTick = positiveLongProperty(
                "rayon.terrain.maxSnapshotNanosPerTick", DEFAULT_MAX_SNAPSHOT_NANOS_PER_TICK);
        this.sectionGraceTicks = positiveLongProperty(
                "rayon.terrain.sectionGraceTicks", DEFAULT_SECTION_GRACE_TICKS);
    }

    /** 서버 스레드에서 호출한다. */
    public void updateDemand(List<ElementRigidBody.ServerBodySnapshot> bodies, long gameTick) {
        drainAppliedRevisions();
        for (TerrainSectionEntry entry : entries.values()) {
            entry.beginDemandPass();
        }

        for (ElementRigidBody.ServerBodySnapshot body : bodies) {
            if (!body.terrainLoading() || !body.active()) {
                continue;
            }

            double speed = Math.sqrt(Math.max(0.0, body.speedSquared()));
            double preload = Math.min(16.0, 2.0 + speed * 0.20);
            AABB required = body.bounds().inflate(preload);
            if (!isFinite(required)) {
                continue;
            }

            int minSectionX = SectionPos.blockToSectionCoord(Mth.floor(required.minX));
            int minSectionY = SectionPos.blockToSectionCoord(Mth.floor(required.minY));
            int minSectionZ = SectionPos.blockToSectionCoord(Mth.floor(required.minZ));
            int maxSectionX = SectionPos.blockToSectionCoord(Mth.floor(required.maxX));
            int maxSectionY = SectionPos.blockToSectionCoord(Mth.floor(required.maxY));
            int maxSectionZ = SectionPos.blockToSectionCoord(Mth.floor(required.maxZ));

            for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
                        long sectionKey = SectionPos.asLong(sectionX, sectionY, sectionZ);
                        TerrainSectionEntry entry = entries.get(sectionKey);
                        if (entry == null) {
                            entry = new TerrainSectionEntry(sectionKey, nextRevision(sectionKey), gameTick);
                            entries.put(sectionKey, entry);
                        } else {
                            entry.require(gameTick);
                        }
                    }
                }
            }
        }

        refreshGauges();
    }

    /** 서버 스레드에서 신규·dirty 섹션을 예산 안에서 snapshot한다. */
    public void snapshotPendingSections(ServerLevel level, int budget) {
        int sectionBudget = Math.max(1, Math.min(budget, snapshotSectionsPerTick));
        long started = System.nanoTime();
        int completed = 0;
        long blockProbes = 0;

        List<TerrainSectionEntry> candidates = new ArrayList<>();
        for (TerrainSectionEntry entry : entries.values()) {
            if (entry.requested() && entry.dirty()) {
                candidates.add(entry);
            }
        }
        candidates.sort(Comparator.comparing(TerrainSectionEntry::loaded).reversed());

        for (TerrainSectionEntry entry : candidates) {
            if (completed >= sectionBudget || (completed > 0 && System.nanoTime() - started >= maxSnapshotNanosPerTick)) {
                break;
            }

            int sectionX = SectionPos.x(entry.sectionKey());
            int sectionY = SectionPos.y(entry.sectionKey());
            int sectionZ = SectionPos.z(entry.sectionKey());
            if (!level.hasChunk(sectionX, sectionZ)) {
                entry.markUnloaded();
                continue;
            }

            int capturedRevision = entry.revision();
            Long2ObjectMap<ChunkCache.BlockData> blocks = new Long2ObjectOpenHashMap<>();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            int minX = SectionPos.sectionToBlockCoord(sectionX);
            int minY = SectionPos.sectionToBlockCoord(sectionY);
            int minZ = SectionPos.sectionToBlockCoord(sectionZ);

            for (int x = minX; x < minX + 16; x++) {
                for (int y = minY; y < minY + 16; y++) {
                    for (int z = minZ; z < minZ + 16; z++) {
                        cursor.set(x, y, z);
                        if (level.isOutsideBuildHeight(cursor)) {
                            continue;
                        }
                        blockProbes++;
                        var blockState = level.getBlockState(cursor);
                        if (!ChunkCache.isValidBlock(blockState)) {
                            continue;
                        }

                        BlockPos position = cursor.immutable();
                        blocks.put(position.asLong(), new ChunkCache.BlockData(
                                position,
                                blockState,
                                ShapeCache.getCollisionBoxes(blockState, level, position)
                        ));
                    }
                }
            }

            if (entry.revision() != capturedRevision) {
                metrics.snapshotDiscarded.increment();
                continue;
            }

            TerrainSectionSnapshot snapshot = new TerrainSectionSnapshot(entry.sectionKey(), capturedRevision, blocks);
            entry.installSnapshot(capturedRevision, snapshot.blocks());
            publishedSnapshots.put(entry.sectionKey(), snapshot);
            pendingSnapshots.add(snapshot);
            completed++;
        }

        long elapsed = System.nanoTime() - started;
        metrics.snapshotSections.add(completed);
        metrics.snapshotBlockProbes.add(blockProbes);
        metrics.snapshotElapsedNanos.add(elapsed);
        if (completed < candidates.size()
                && (completed >= sectionBudget || elapsed >= maxSnapshotNanosPerTick)) {
            metrics.snapshotBudgetExceeded.increment();
        }
        refreshGauges();
    }

    /** 서버 스레드에서 호출한다. 사용 중인 섹션만 dirty로 만든다. */
    public void markDirty(BlockPos position) {
        long centerSectionKey = SectionPos.of(position).asLong();
        markSectionDirty(centerSectionKey);
        for (Direction direction : Direction.values()) {
            long adjacentSectionKey = SectionPos.of(position.relative(direction)).asLong();
            if (adjacentSectionKey != centerSectionKey) {
                markSectionDirty(adjacentSectionKey);
            }
        }
        refreshGauges();
    }

    private void markSectionDirty(long sectionKey) {
        TerrainSectionEntry entry = entries.get(sectionKey);
        if (entry == null) {
            return;
        }
        entry.markDirty(nextRevision(sectionKey));
    }

    /** 청크 unload는 grace period 없이 즉시 무효화한다. */
    public void evictChunk(int chunkX, int chunkZ) {
        LongArrayList evicted = new LongArrayList();
        for (TerrainSectionEntry entry : entries.values()) {
            if (SectionPos.x(entry.sectionKey()) == chunkX && SectionPos.z(entry.sectionKey()) == chunkZ) {
                evicted.add(entry.sectionKey());
            }
        }
        for (long sectionKey : evicted) {
            evictSection(sectionKey);
        }
        refreshGauges();
    }

    /** 수요에서 빠진 뒤 grace period가 지난 섹션을 제거한다. */
    public void evictExpired(long gameTick) {
        LongArrayList evicted = new LongArrayList();
        for (TerrainSectionEntry entry : entries.values()) {
            if (!entry.requested() && gameTick - entry.lastRequiredTick() >= sectionGraceTicks) {
                evicted.add(entry.sectionKey());
            }
        }
        for (long sectionKey : evicted) {
            evictSection(sectionKey);
        }
        refreshGauges();
    }

    public void clear() {
        entries.clear();
        publishedSnapshots.clear();
        pendingSnapshots.clear();
        appliedRevisions.clear();
        synchronized (revisionLock) {
            currentRevisions.clear();
        }
        refreshGauges();
    }

    public TerrainSectionSnapshot pollPendingSnapshot() {
        return pendingSnapshots.poll();
    }

    /** revision 확인과 Bullet diff 적용 사이에 dirty 변경이 끼어들지 않게 한다. */
    public boolean applyIfCurrent(TerrainSectionSnapshot snapshot, Runnable action) {
        synchronized (revisionLock) {
            Integer revision = currentRevisions.get(snapshot.sectionKey());
            if (revision == null || revision != snapshot.revision()) {
                metrics.snapshotDiscarded.increment();
                return false;
            }
            action.run();
            return true;
        }
    }

    /** 물리 스레드 적용 완료를 서버 스레드용 queue에 기록한다. */
    public void markApplied(long sectionKey, int revision) {
        appliedRevisions.add(new AppliedRevision(sectionKey, revision));
    }

    public Optional<ChunkCache.BlockData> getBlockData(BlockPos position) {
        TerrainSectionSnapshot snapshot = publishedSnapshots.get(SectionPos.of(position).asLong());
        return snapshot == null ? Optional.empty() : Optional.ofNullable(snapshot.blocks().get(position.asLong()));
    }

    public List<ChunkCache.BlockData> getBlockData() {
        List<ChunkCache.BlockData> output = new ArrayList<>();
        for (TerrainSectionSnapshot snapshot : publishedSnapshots.values()) {
            output.addAll(snapshot.blocks().values());
        }
        return output;
    }

    public boolean isBlockRequired(BlockPos position) {
        TerrainSectionEntry entry = entries.get(SectionPos.of(position).asLong());
        return entry != null && entry.requested();
    }

    public int snapshotSectionsPerTick() {
        return snapshotSectionsPerTick;
    }

    public void recordBodyDiff(long added, long changed, long removed) {
        metrics.bodyDiffAdded.add(added);
        metrics.bodyDiffChanged.add(changed);
        metrics.bodyDiffRemoved.add(removed);
    }

    public void logMetrics(long gameTick) {
        if (lastMetricsTick != Long.MIN_VALUE && gameTick - lastMetricsTick < METRICS_INTERVAL_TICKS) {
            return;
        }
        lastMetricsTick = gameTick;
        Rayon.LOGGER.debug(
                "{} terrain.section_cache.entries={} terrain.section_cache.required={} terrain.section_cache.dirty={} "
                        + "terrain.section_cache.cooling={} terrain.snapshot.sections={} terrain.snapshot.block_probes={} "
                        + "terrain.snapshot.elapsed_nanos={} terrain.snapshot.budget_exceeded={} terrain.snapshot.discarded={} "
                        + "terrain.body_diff.added={} terrain.body_diff.changed={} terrain.body_diff.removed={}",
                space.getLevel().dimension().location(),
                metrics.entries,
                metrics.required,
                metrics.dirty,
                metrics.cooling,
                metrics.snapshotSections.sumThenReset(),
                metrics.snapshotBlockProbes.sumThenReset(),
                metrics.snapshotElapsedNanos.sumThenReset(),
                metrics.snapshotBudgetExceeded.sumThenReset(),
                metrics.snapshotDiscarded.sumThenReset(),
                metrics.bodyDiffAdded.sumThenReset(),
                metrics.bodyDiffChanged.sumThenReset(),
                metrics.bodyDiffRemoved.sumThenReset()
        );
    }

    private void evictSection(long sectionKey) {
        int revision = nextRevision(sectionKey);
        entries.remove(sectionKey);
        publishedSnapshots.remove(sectionKey);
        pendingSnapshots.add(new TerrainSectionSnapshot(sectionKey, revision, new Long2ObjectOpenHashMap<>()));
    }

    private int nextRevision(long sectionKey) {
        synchronized (revisionLock) {
            int next = currentRevisions.getOrDefault(sectionKey, 0) + 1;
            currentRevisions.put(sectionKey, next);
            return next;
        }
    }

    private void drainAppliedRevisions() {
        AppliedRevision applied;
        while ((applied = appliedRevisions.poll()) != null) {
            TerrainSectionEntry entry = entries.get(applied.sectionKey());
            if (entry != null) {
                entry.markApplied(applied.revision());
            }
        }
    }

    private void refreshGauges() {
        int required = 0;
        int dirty = 0;
        int cooling = 0;
        for (TerrainSectionEntry entry : entries.values()) {
            if (entry.requested()) {
                required++;
            } else {
                cooling++;
            }
            if (entry.dirty()) {
                dirty++;
            }
        }
        metrics.entries = entries.size();
        metrics.required = required;
        metrics.dirty = dirty;
        metrics.cooling = cooling;
    }

    private static boolean isFinite(AABB box) {
        return Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ);
    }

    private static int positiveIntProperty(String name, int fallback) {
        int value = Integer.getInteger(name, fallback);
        return value > 0 ? value : fallback;
    }

    private static long positiveLongProperty(String name, long fallback) {
        long value = Long.getLong(name, fallback);
        return value > 0 ? value : fallback;
    }

    private static final class Metrics {
        private volatile int entries;
        private volatile int required;
        private volatile int dirty;
        private volatile int cooling;
        private final LongAdder snapshotSections = new LongAdder();
        private final LongAdder snapshotBlockProbes = new LongAdder();
        private final LongAdder snapshotElapsedNanos = new LongAdder();
        private final LongAdder snapshotBudgetExceeded = new LongAdder();
        private final LongAdder snapshotDiscarded = new LongAdder();
        private final LongAdder bodyDiffAdded = new LongAdder();
        private final LongAdder bodyDiffChanged = new LongAdder();
        private final LongAdder bodyDiffRemoved = new LongAdder();
    }

    private record AppliedRevision(long sectionKey, int revision) {
    }
}
