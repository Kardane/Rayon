package dev.lazurite.rayon.impl.bullet.collision.space.generator;

import dev.lazurite.rayon.impl.bullet.collision.body.ElementRigidBody;
import dev.lazurite.rayon.impl.bullet.collision.body.TerrainRigidBody;
import dev.lazurite.rayon.impl.bullet.collision.space.MinecraftSpace;
import dev.lazurite.rayon.impl.bullet.collision.space.cache.ChunkCache;
import dev.lazurite.rayon.impl.bullet.collision.space.cache.TerrainSectionSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** 물리 스레드에서 지형 snapshot을 Bullet body diff로 반영한다. */
public final class TerrainGenerator {
    private TerrainGenerator() {
    }

    public static void step(MinecraftSpace space) {
        if (space.getTerrainMode().usesSectionCache()) {
            applyPendingSectionSnapshots(space);
        } else {
            legacyStep(space);
        }
    }

    private static void applyPendingSectionSnapshots(MinecraftSpace space) {
        TerrainSectionSnapshot snapshot;
        while ((snapshot = space.getTerrainSectionCache().pollPendingSnapshot()) != null) {
            TerrainSectionSnapshot current = snapshot;
            boolean applied = space.getTerrainSectionCache().applyIfCurrent(current,
                    () -> applySectionSnapshot(space, current));
            if (applied) {
                space.getTerrainSectionCache().markApplied(current.sectionKey(), current.revision());
            }
        }
    }

    private static void applySectionSnapshot(MinecraftSpace space, TerrainSectionSnapshot snapshot) {
        int sectionX = SectionPos.x(snapshot.sectionKey());
        int sectionY = SectionPos.y(snapshot.sectionKey());
        int sectionZ = SectionPos.z(snapshot.sectionKey());

        Map<BlockPos, TerrainRigidBody> remaining = new HashMap<>();
        space.getTerrainMap().forEach((position, terrain) -> {
            if (SectionPos.blockToSectionCoord(position.getX()) == sectionX
                    && SectionPos.blockToSectionCoord(position.getY()) == sectionY
                    && SectionPos.blockToSectionCoord(position.getZ()) == sectionZ) {
                remaining.put(position, terrain);
            }
        });

        long added = 0;
        long changed = 0;
        long removed = 0;
        for (ChunkCache.BlockData blockData : snapshot.blocks().values()) {
            TerrainRigidBody terrain = remaining.remove(blockData.blockPos());
            if (terrain == null) {
                space.addCollisionObject(TerrainRigidBody.from(space, blockData));
                added++;
            } else if (!terrain.matches(blockData)) {
                space.removeCollisionObject(terrain);
                space.addCollisionObject(TerrainRigidBody.from(space, blockData));
                changed++;
            }
        }

        for (TerrainRigidBody terrain : remaining.values()) {
            space.removeCollisionObject(terrain);
            removed++;
        }
        space.getTerrainSectionCache().recordBodyDiff(added, changed, removed);
    }

    /** 기능 플래그로 되돌릴 수 있도록 기존 블록 범위 스캔을 유지한다. */
    private static void legacyStep(MinecraftSpace space) {
        final var chunkCache = space.getChunkCache();
        final var keep = new HashSet<TerrainRigidBody>();

        for (var rigidBody : space.getRigidBodiesByClass(ElementRigidBody.class)) {
            if (!rigidBody.terrainLoadingEnabled() || !rigidBody.isActive()) {
                continue;
            }

            final var aabb = rigidBody.getCurrentMinecraftBoundingBox().inflate(0.5f);
            BlockPos.betweenClosedStream(aabb).forEach(blockPos -> {
                chunkCache.getBlockData(blockPos).ifPresent(blockData -> {
                    space.getTerrainObjectAt(blockPos).ifPresentOrElse(terrain -> {
                        if (!terrain.matches(blockData)) {
                            space.removeCollisionObject(terrain);
                            final var replacement = TerrainRigidBody.from(space, blockData);
                            space.addCollisionObject(replacement);
                            keep.add(replacement);
                        } else {
                            keep.add(terrain);
                        }
                    }, () -> {
                        final var terrain = TerrainRigidBody.from(space, blockData);
                        space.addCollisionObject(terrain);
                        keep.add(terrain);
                    });
                });
            });
        }

        space.getTerrainMap().forEach((blockPos, terrain) -> {
            if (!keep.contains(terrain)) {
                space.removeTerrainObjectAt(blockPos);
            }
        });
    }
}
