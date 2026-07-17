package dev.lazurite.rayon.api.event.collision;

import dev.lazurite.rayon.impl.bullet.collision.body.ElementRigidBody;
import dev.lazurite.rayon.impl.bullet.collision.space.MinecraftSpace;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/**
 * 물리 공간 내부 이벤트다. INIT을 제외한 콜백은 Rayon 물리 스레드에서 호출되며,
 * 콜백에서 Minecraft Level 또는 Entity를 직접 변경하면 안 된다.
 */
public final class PhysicsSpaceEvents {
    public static final Event<Init> INIT = EventFactory.createArrayBacked(Init.class,
            callbacks -> space -> {
                for (Init callback : callbacks) {
                    callback.onInit(space);
                }
            });
    public static final Event<Step> STEP = EventFactory.createArrayBacked(Step.class,
            callbacks -> space -> {
                for (Step callback : callbacks) {
                    callback.onStep(space);
                }
            });
    public static final Event<ElementAdded> ELEMENT_ADDED = EventFactory.createArrayBacked(ElementAdded.class,
            callbacks -> (space, rigidBody) -> {
                for (ElementAdded callback : callbacks) {
                    callback.onElementAdded(space, rigidBody);
                }
            });
    public static final Event<ElementRemoved> ELEMENT_REMOVED = EventFactory.createArrayBacked(ElementRemoved.class,
            callbacks -> (space, rigidBody) -> {
                for (ElementRemoved callback : callbacks) {
                    callback.onElementRemoved(space, rigidBody);
                }
            });

    private PhysicsSpaceEvents() {
    }

    @FunctionalInterface
    public interface Init {
        void onInit(MinecraftSpace space);
    }

    @FunctionalInterface
    public interface Step {
        void onStep(MinecraftSpace space);
    }

    @FunctionalInterface
    public interface ElementAdded {
        void onElementAdded(MinecraftSpace space, ElementRigidBody rigidBody);
    }

    @FunctionalInterface
    public interface ElementRemoved {
        void onElementRemoved(MinecraftSpace space, ElementRigidBody rigidBody);
    }
}
