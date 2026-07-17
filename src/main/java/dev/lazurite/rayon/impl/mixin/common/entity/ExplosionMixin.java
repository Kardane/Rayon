package dev.lazurite.rayon.impl.mixin.common.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lazurite.rayon.api.EntityPhysicsElement;
import dev.lazurite.rayon.impl.bullet.math.Convert;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 1.21.8 ServerExplosion의 계산된 knockback을 Bullet impulse로도 전달한다. */
@Mixin(ServerExplosion.class)
public abstract class ExplosionMixin {
    @WrapOperation(
            method = "hurtEntities",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;push(Lnet/minecraft/world/phys/Vec3;)V"
            )
    )
    private void rayon$enqueueExplosionImpulse(Entity entity, Vec3 knockback, Operation<Void> original) {
        original.call(entity, knockback);
        if (EntityPhysicsElement.is(entity)) {
            var rigidBody = EntityPhysicsElement.get(entity).getRigidBody();
            rigidBody.enqueueImpulse(Convert.toBullet(knockback).multLocal(rigidBody.getMass()));
        }
    }
}
