package dev.lazurite.rayon.impl.bullet.collision.space.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;

/** 서버 스레드가 소유하는 단일 청크 섹션의 지형 상태다. */
public final class TerrainSectionEntry {
    private final long sectionKey;
    private int revision;
    private int appliedRevision;
    private long lastRequiredTick;
    private boolean requested;
    private boolean dirty;
    private boolean loaded;
    private Long2ObjectMap<ChunkCache.BlockData> blocks = Long2ObjectMaps.emptyMap();

    TerrainSectionEntry(long sectionKey, int revision, long gameTick) {
        this.sectionKey = sectionKey;
        this.revision = revision;
        this.lastRequiredTick = gameTick;
        this.requested = true;
        this.dirty = true;
    }

    public long sectionKey() {
        return sectionKey;
    }

    public int revision() {
        return revision;
    }

    public int appliedRevision() {
        return appliedRevision;
    }

    public long lastRequiredTick() {
        return lastRequiredTick;
    }

    public boolean requested() {
        return requested;
    }

    public boolean dirty() {
        return dirty;
    }

    public boolean loaded() {
        return loaded;
    }

    public Long2ObjectMap<ChunkCache.BlockData> blocks() {
        return blocks;
    }

    void beginDemandPass() {
        requested = false;
    }

    void require(long gameTick) {
        requested = true;
        lastRequiredTick = gameTick;
    }

    void markDirty(int revision) {
        this.revision = revision;
        dirty = true;
    }

    void markUnloaded() {
        loaded = false;
        dirty = true;
    }

    void installSnapshot(int revision, Long2ObjectMap<ChunkCache.BlockData> blocks) {
        this.revision = revision;
        this.blocks = blocks;
        loaded = true;
        dirty = false;
    }

    void markApplied(int revision) {
        if (this.revision == revision) {
            appliedRevision = revision;
        }
    }
}
