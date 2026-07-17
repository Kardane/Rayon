package dev.lazurite.rayon.api.event.collision;

import dev.lazurite.rayon.api.PhysicsElement;
import dev.lazurite.rayon.impl.bullet.collision.body.TerrainRigidBody;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/** 충돌 콜백은 Rayon 물리 스레드에서 순서대로 호출된다. */
public final class ElementCollisionEvents {
    public static final Event<BlockCollision> BLOCK_COLLISION = EventFactory.createArrayBacked(BlockCollision.class,
            callbacks -> (element, terrain, impulse) -> {
                for (BlockCollision callback : callbacks) {
                    callback.onCollide(element, terrain, impulse);
                }
            });
    public static final Event<ElementCollision> ELEMENT_COLLISION = EventFactory.createArrayBacked(ElementCollision.class,
            callbacks -> (first, second, impulse) -> {
                for (ElementCollision callback : callbacks) {
                    callback.onCollide(first, second, impulse);
                }
            });

    private ElementCollisionEvents() {
    }

    @FunctionalInterface
    public interface BlockCollision {
        void onCollide(PhysicsElement<?> element, TerrainRigidBody terrainObject, float impulse);
    }

    @FunctionalInterface
    public interface ElementCollision {
        void onCollide(PhysicsElement<?> first, PhysicsElement<?> second, float impulse);
    }
}
