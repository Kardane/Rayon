package dev.lazurite.rayon.impl.bullet.collision.space.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.IdentityHashMap;
import java.util.List;

/** 서버 스레드에서 충돌 형상의 순수 수치 snapshot만 만든다. */
public final class ShapeCache {
    private static final List<AABB> FALLBACK_BOXES = List.of(new AABB(0, 0, 0, 1, 1, 1));
    private static final IdentityHashMap<BlockState, List<AABB>> BOXES = new IdentityHashMap<>();

    private ShapeCache() {
    }

    public static List<AABB> getCollisionBoxes(BlockState blockState, ServerLevel level, BlockPos blockPos) {
        if (blockState.getBlock().hasDynamicShape()) {
            return createCollisionBoxes(blockState, level, blockPos);
        }

        synchronized (BOXES) {
            return BOXES.computeIfAbsent(blockState,
                    state -> createCollisionBoxes(state, level, BlockPos.ZERO));
        }
    }

    private static List<AABB> createCollisionBoxes(BlockState blockState, ServerLevel level, BlockPos blockPos) {
        List<AABB> boxes = blockState.getCollisionShape(level, blockPos).toAabbs();
        return boxes.isEmpty() ? FALLBACK_BOXES : List.copyOf(boxes);
    }
}
