package dev.lazurite.rayon.impl.mixin.common;

import dev.lazurite.rayon.impl.event.ServerEventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fabric API에 없는 최소 block update 알림만 보완한다. */
@Mixin(Level.class)
public abstract class BlockUpdateMixin {
    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN")
    )
    private void rayon$markChangedSection(BlockPos blockPos, BlockState blockState, int flags, int recursionLeft,
                                          CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ() && (Object) this instanceof ServerLevel serverLevel) {
            ServerEventHandler.onBlockUpdate(serverLevel, blockPos);
        }
    }
}
