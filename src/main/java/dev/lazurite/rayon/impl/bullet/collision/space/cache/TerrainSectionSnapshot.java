package dev.lazurite.rayon.impl.bullet.collision.space.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/** 물리 스레드로 전달되는 불변 지형 snapshot이다. 빈 blocks는 섹션 제거도 표현한다. */
public record TerrainSectionSnapshot(
        long sectionKey,
        int revision,
        Long2ObjectMap<ChunkCache.BlockData> blocks
) {
    public TerrainSectionSnapshot {
        blocks = Long2ObjectMaps.unmodifiable(new Long2ObjectOpenHashMap<>(blocks));
    }
}
