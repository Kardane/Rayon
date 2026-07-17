package dev.lazurite.rayon.impl.bullet.collision.space.cache;

import dev.lazurite.rayon.impl.Rayon;
import dev.lazurite.rayon.impl.bullet.collision.space.MinecraftSpace;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SimpleChunkCache implements ChunkCache {
    private static final int MAX_BLOCKS_PER_BODY = 8_192;
    private static final int MAX_BLOCKS_PER_TICK = 32_768;
    private static final int MAX_SCAN_AXIS = 48;
    private static final long SLOW_REFRESH_NANOS = 25_000_000L;
    private static final long WARNING_INTERVAL_NANOS = 5_000_000_000L;
    private final MinecraftSpace space;
    private final Map<BlockPos, BlockData> legacyBlockData;
    private final List<FluidColumn> fluidColumns;
    private final Long2ObjectMap<List<FluidColumn>> fluidColumnByIndex;
    private final LongSet activePositions;
    private final Long2ObjectMap<List<BlockPos>> activeColumn;
    private long lastSlowWarningAt;
    private long lastBudgetWarningAt;

    SimpleChunkCache(MinecraftSpace space) {
        this.space = space;
        this.legacyBlockData = new ConcurrentHashMap<>();
        this.fluidColumns = new ArrayList<>();
        this.activePositions = new LongOpenHashSet();
        this.activeColumn = new Long2ObjectOpenHashMap<>(65536);
        this.fluidColumnByIndex = new Long2ObjectOpenHashMap<>(65536);
    }

    @Override
    public synchronized void loadFluidData(BlockPos blockPos) {
        final var level = space.getLevel();

        if (!level.getFluidState(blockPos).isEmpty()) {
            var columns = this.fluidColumnByIndex.get(columnIndex(blockPos));

            if (columns == null || columns.stream().noneMatch(column -> column.contains(blockPos))) {
                var column = new FluidColumn(new BlockPos(blockPos), level);
                this.fluidColumns.add(column);
                this.fluidColumnByIndex.computeIfAbsent(column.getIndex(), (a) -> new ArrayList<>()).add(column);
            }
        }
    }

    @Override
    public synchronized void loadBlockData(BlockPos blockPos) {
        if (space.getTerrainMode().usesSectionCache()) {
            space.getTerrainSectionCache().markDirty(blockPos);
            return;
        }
        final var level = space.getLevel();
        final var blockState = level.getBlockState(blockPos);

        loadBlockData(blockPos.immutable(), level, blockState);
    }

    private void loadBlockData(BlockPos blockPos, ServerLevel level, BlockState blockState) {
        if (ChunkCache.isValidBlock(blockState)) {
            this.legacyBlockData.put(blockPos, new BlockData(
                    blockPos,
                    blockState,
                    ShapeCache.getCollisionBoxes(blockState, level, blockPos)
            ));
        } else {
            this.legacyBlockData.remove(blockPos);
        }
    }

    @Override
    public synchronized void refreshAll() {
        long started = System.nanoTime();
        final var level = space.getLevel();
        final boolean legacy = !space.getTerrainMode().usesSectionCache();
        this.activePositions.clear();
        this.activeColumn.clear();
        int scannedBlocks = 0;
        boolean budgetExceeded = false;

        for (var rigidBody : space.getElementBodiesSnapshot()) {
            var snapshot = rigidBody.getServerSnapshot();
            if (!snapshot.terrainLoading() || !snapshot.active()) {
                continue;
            }

            final var aabb = snapshot.bounds().inflate(1.0f + Mth.sqrt(snapshot.speedSquared()) / 20);
            int minX = Mth.floor(aabb.minX);
            int minY = Mth.floor(aabb.minY);
            int minZ = Mth.floor(aabb.minZ);
            int maxX = Mth.floor(aabb.maxX);
            int maxY = Mth.floor(aabb.maxY);
            int maxZ = Mth.floor(aabb.maxZ);

            int[] xRange = clampRange(minX, maxX);
            int[] yRange = clampRange(minY, maxY);
            int[] zRange = clampRange(minZ, maxZ);
            if (xRange[0] != minX || xRange[1] != maxX
                    || yRange[0] != minY || yRange[1] != maxY
                    || zRange[0] != minZ || zRange[1] != maxZ) {
                budgetExceeded = true;
            }

            int bodyScans = 0;
            bodyScan:
            for (int x = xRange[0]; x <= xRange[1]; x++) {
                for (int y = yRange[0]; y <= yRange[1]; y++) {
                    for (int z = zRange[0]; z <= zRange[1]; z++) {
                        if (bodyScans >= MAX_BLOCKS_PER_BODY || scannedBlocks >= MAX_BLOCKS_PER_TICK) {
                            budgetExceeded = true;
                            break bodyScan;
                        }
                        bodyScans++;
                        scannedBlocks++;

                        BlockPos pos = new BlockPos(x, y, z);
                        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                                || !this.activePositions.add(pos.asLong())) {
                            continue;
                        }

                        this.activeColumn.computeIfAbsent(columnIndex(pos), ignored -> new ObjectArrayList<>(512)).add(pos);
                        if (legacy) {
                            var previous = this.legacyBlockData.get(pos);
                            final var blockState = level.getBlockState(pos);
                            if (previous == null || previous.blockState() != blockState) {
                                loadBlockData(pos, level, blockState);
                            }
                        }

                        if (this.getFluidColumn(pos).isEmpty()) {
                            loadFluidData(pos);
                        }
                    }
                }
            }

            if (scannedBlocks >= MAX_BLOCKS_PER_TICK) {
                break;
            }
        }

        if (legacy) {
            this.legacyBlockData.keySet().removeIf(blockPos -> !this.activePositions.contains(blockPos.asLong()));
        }
        this.fluidColumns.removeIf(column -> {
            var x = !isInActiveColumn(column);

            if (x) {
                var y = this.fluidColumnByIndex.get(column.getIndex());
                if (y != null) {
                    y.remove(column);
                }
            }

            return x;
        });

        long finished = System.nanoTime();
        long elapsed = finished - started;
        if (budgetExceeded && finished - lastBudgetWarningAt > WARNING_INTERVAL_NANOS) {
            lastBudgetWarningAt = finished;
            Rayon.LOGGER.warn("{} 월드의 지형 snapshot이 안전 한도에서 잘렸습니다. scanned={}, tickLimit={}, bodyLimit={}, axisLimit={}",
                    space.getLevel().dimension().location(), scannedBlocks,
                    MAX_BLOCKS_PER_TICK, MAX_BLOCKS_PER_BODY, MAX_SCAN_AXIS);
        }
        if (elapsed > SLOW_REFRESH_NANOS && finished - lastSlowWarningAt > WARNING_INTERVAL_NANOS) {
            lastSlowWarningAt = finished;
            Rayon.LOGGER.warn("{} 월드의 지형 snapshot 갱신이 {} ms 걸렸습니다. blocks={}, fluids={}",
                    space.getLevel().dimension().location(), elapsed / 1_000_000.0,
                    legacy ? legacyBlockData.size() : space.getTerrainSectionCache().getBlockData().size(), fluidColumns.size());
        }
    }

    @Override
    public synchronized void evictChunk(int chunkX, int chunkZ) {
        legacyBlockData.keySet().removeIf(pos -> (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ);

        var activeIterator = activePositions.iterator();
        while (activeIterator.hasNext()) {
            long packed = activeIterator.nextLong();
            if ((BlockPos.getX(packed) >> 4) == chunkX && (BlockPos.getZ(packed) >> 4) == chunkZ) {
                activeIterator.remove();
            }
        }

        var columnIterator = activeColumn.long2ObjectEntrySet().iterator();
        while (columnIterator.hasNext()) {
            long key = columnIterator.next().getLongKey();
            int blockX = (int) (key >> 32);
            int blockZ = (int) key;
            if ((blockX >> 4) == chunkX && (blockZ >> 4) == chunkZ) {
                columnIterator.remove();
            }
        }

        fluidColumns.removeIf(column -> {
            BlockPos pos = column.getTop().blockPos();
            return (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ;
        });
        fluidColumnByIndex.clear();
        for (FluidColumn column : fluidColumns) {
            fluidColumnByIndex.computeIfAbsent(column.getIndex(), ignored -> new ArrayList<>()).add(column);
        }
    }

    @Override
    public synchronized void clear() {
        legacyBlockData.clear();
        fluidColumns.clear();
        fluidColumnByIndex.clear();
        activePositions.clear();
        activeColumn.clear();
    }

    private static long columnIndex(BlockPos blockPos) {
        return Integer.toUnsignedLong(blockPos.getX()) << 32l | Integer.toUnsignedLong(blockPos.getZ());
    }

    private static int[] clampRange(int minimum, int maximum) {
        int size = maximum - minimum + 1;
        if (size <= MAX_SCAN_AXIS) {
            return new int[]{minimum, maximum};
        }
        int center = minimum + (size / 2);
        int lower = center - (MAX_SCAN_AXIS / 2);
        return new int[]{lower, lower + MAX_SCAN_AXIS - 1};
    }

    private boolean isInActiveColumn(FluidColumn column) {
        var list = this.activeColumn.get(column.getIndex());
        if (list == null) {
            return false;
        }

        for (var e : list) {
            if (column.contains(e)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public MinecraftSpace getSpace() {
        return this.space;
    }

    @Override
    public synchronized List<BlockData> getBlockData() {
        if (space.getTerrainMode().usesSectionCache()) {
            return space.getTerrainSectionCache().getBlockData();
        }
        return new ArrayList<>(this.legacyBlockData.values());
    }

    @Override
    public synchronized List<FluidColumn> getFluidColumns() {
        return new ArrayList<>(this.fluidColumns);
    }

    @Override
    public synchronized Optional<BlockData> getBlockData(BlockPos blockPos) {
        if (space.getTerrainMode().usesSectionCache()) {
            return space.getTerrainSectionCache().getBlockData(blockPos);
        }
        return Optional.ofNullable(this.legacyBlockData.get(blockPos));
    }

    @Override
    public synchronized Optional<FluidColumn> getFluidColumn(BlockPos blockPos) {
        var allColumns = this.fluidColumnByIndex.get(columnIndex(blockPos));

        if (allColumns != null) {
            for (var column : allColumns) {
                if (column.contains(blockPos)) {
                    return Optional.of(column);
                }
            }
        }

        return Optional.empty();
    }

    @Override
    public synchronized boolean isActive(BlockPos blockPos) {
        return this.activePositions.contains(blockPos.asLong());
    }
}
