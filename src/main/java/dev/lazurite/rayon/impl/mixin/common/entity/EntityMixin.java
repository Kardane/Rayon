package dev.lazurite.rayon.impl.mixin.common.entity;

import dev.lazurite.rayon.api.EntityPhysicsElement;
import dev.lazurite.rayon.impl.serialization.PhysicsState;
import dev.lazurite.rayon.impl.serialization.PhysicsStateHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(Entity.class)
public abstract class EntityMixin implements PhysicsStateHolder {
    @Unique
    private PhysicsState rayon$pendingPhysicsState;

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void rayon$skipPhysicsEntityPush(Entity other, CallbackInfo callback) {
        Entity self = (Entity) (Object) this;
        if (EntityPhysicsElement.is(self) && EntityPhysicsElement.is(other)) {
            callback.cancel();
        }
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void rayon$skipVanillaMovement(MoverType moverType, Vec3 movement, CallbackInfo callback) {
        if (EntityPhysicsElement.is((Entity) (Object) this)) {
            callback.cancel();
        }
    }

    @Inject(method = "saveWithoutId", at = @At("TAIL"))
    private void rayon$writePhysicsState(ValueOutput output, CallbackInfo callback) {
        if ((Object) this instanceof EntityPhysicsElement element && element.getRigidBody() != null) {
            element.getRigidBody().getPersistenceSnapshot().write(output);
        }
    }

    @Inject(method = "load", at = @At("TAIL"))
    private void rayon$readPhysicsState(ValueInput input, CallbackInfo callback) {
        if ((Object) this instanceof EntityPhysicsElement) {
            PhysicsState.read(input).ifPresent(this::rayon$setPendingPhysicsState);
        }
    }

    @Override
    public void rayon$setPendingPhysicsState(PhysicsState state) {
        this.rayon$pendingPhysicsState = state;
    }

    @Override
    public Optional<PhysicsState> rayon$takePendingPhysicsState() {
        PhysicsState state = this.rayon$pendingPhysicsState;
        this.rayon$pendingPhysicsState = null;
        return Optional.ofNullable(state);
    }
}
