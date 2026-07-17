package dev.lazurite.rayon.impl.bullet.collision.space.generator;

import com.jme3.math.Vector3f;
import dev.lazurite.rayon.api.EntityPhysicsElement;
import dev.lazurite.rayon.impl.bullet.collision.body.EntityRigidBody;
import dev.lazurite.rayon.impl.bullet.collision.space.MinecraftSpace;
import dev.lazurite.rayon.impl.bullet.math.Convert;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;

/** 서버 스레드에서 바닐라 엔티티 충돌을 snapshot 기반 impulse 명령으로 변환한다. */
public final class EntityCollisionGenerator {
    private EntityCollisionGenerator() {
    }

    public static void step(MinecraftSpace space) {
        for (var elementBody : space.getElementBodiesSnapshot()) {
            if (!(elementBody instanceof EntityRigidBody rigidBody)
                    || rigidBody.getElement().skipVanillaEntityCollisions()) {
                continue;
            }

            var snapshot = rigidBody.getServerSnapshot();
            var bodyBox = snapshot.bounds();
            if (!snapshot.active() || bodyBox.getSize() <= 0.0) {
                continue;
            }

            for (Entity entity : space.getLevel().getEntitiesOfClass(Entity.class, bodyBox, candidate ->
                    !candidate.isSpectator()
                            && !EntityPhysicsElement.is(candidate)
                            && (candidate instanceof LivingEntity || candidate instanceof Boat || candidate instanceof Minecart))) {
                var bodyCenter = Convert.toBullet(bodyBox.getCenter());
                var entityPosition = Convert.toBullet(entity.position().add(0, entity.getBoundingBox().getYsize() * 0.5, 0));
                var normal = bodyCenter.subtract(entityPosition).multLocal(new Vector3f(1, 0, 1));
                if (normal.lengthSquared() < 1.0e-6f) {
                    continue;
                }
                normal = normal.normalize();

                var intersection = entity.getBoundingBox().intersect(bodyBox);
                float ratio = (float) Math.max(0.0, intersection.getSize() / bodyBox.getSize());
                Vector3f impulse = normal.multLocal(ratio * snapshot.mass());
                rigidBody.enqueueImpulse(impulse);
            }
        }
    }
}
