package dev.lazurite.rayon.impl.bullet.collision.body;

import com.jme3.math.Vector3f;
import dev.lazurite.rayon.api.EntityPhysicsElement;
import dev.lazurite.rayon.impl.bullet.collision.body.shape.MinecraftShape;
import dev.lazurite.rayon.impl.bullet.collision.space.MinecraftSpace;

public class EntityRigidBody extends ElementRigidBody {
    private final int entityId;

    public EntityRigidBody(EntityPhysicsElement element, MinecraftSpace space, MinecraftShape shape,
                           float mass, float dragCoefficient, float friction, float restitution) {
        super(element, space, shape, mass, dragCoefficient, friction, restitution);
        this.entityId = element.cast().getId();
    }

    public EntityRigidBody(EntityPhysicsElement element, MinecraftSpace space, MinecraftShape shape) {
        this(element, space, shape, 10.0f, 0.25f, 1.0f, 0.5f);
    }

    public EntityRigidBody(EntityPhysicsElement element) {
        this(element, MinecraftSpace.get(element.cast().level()), element.createShape());
    }

    @Override
    public EntityPhysicsElement getElement() {
        return (EntityPhysicsElement) super.getElement();
    }

    public int getEntityId() {
        return entityId;
    }

    public void enqueueImpulse(Vector3f impulse) {
        Vector3f copy = impulse.clone();
        getSpace().getExecutor().execute(() -> {
            applyCentralImpulse(copy);
            activate();
        });
    }
}
