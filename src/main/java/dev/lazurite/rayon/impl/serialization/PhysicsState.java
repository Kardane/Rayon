package dev.lazurite.rayon.impl.serialization;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import dev.lazurite.rayon.impl.bullet.collision.body.ElementRigidBody;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Locale;
import java.util.Optional;

public record PhysicsState(
        Quaternion orientation,
        Vector3f linearVelocity,
        Vector3f angularVelocity,
        float mass,
        float dragCoefficient,
        float friction,
        float restitution,
        boolean terrainLoading,
        ElementRigidBody.BuoyancyType buoyancyType,
        ElementRigidBody.DragType dragType
) {
    public static final int DATA_VERSION = 2;

    public PhysicsState {
        orientation = QuaternionCodec.sanitize(orientation);
        linearVelocity = Vector3fCodec.sanitize(linearVelocity);
        angularVelocity = Vector3fCodec.sanitize(angularVelocity);
        mass = finiteNonNegative(mass, 10.0f);
        dragCoefficient = finiteNonNegative(dragCoefficient, 0.25f);
        friction = finiteNonNegative(friction, 1.0f);
        restitution = finiteNonNegative(restitution, 0.5f);
        buoyancyType = buoyancyType == null ? ElementRigidBody.BuoyancyType.WATER : buoyancyType;
        dragType = dragType == null ? ElementRigidBody.DragType.SIMPLE : dragType;
    }

    public static PhysicsState capture(ElementRigidBody body) {
        return new PhysicsState(
                body.getPhysicsRotation(new Quaternion()),
                body.getLinearVelocity(new Vector3f()),
                body.getAngularVelocity(new Vector3f()),
                body.getMass(),
                body.getDragCoefficient(),
                body.getFriction(),
                body.getRestitution(),
                body.terrainLoadingEnabled(),
                body.getBuoyancyType(),
                body.getDragType()
        );
    }

    public void write(ValueOutput output) {
        ValueOutput rayon = output.child("rayon");
        rayon.putInt("data_version", DATA_VERSION);
        QuaternionCodec.write(rayon, "orientation", orientation);
        Vector3fCodec.write(rayon, "linear_velocity", linearVelocity);
        Vector3fCodec.write(rayon, "angular_velocity", angularVelocity);
        rayon.putFloat("mass", mass);
        rayon.putFloat("drag_coefficient", dragCoefficient);
        rayon.putFloat("friction", friction);
        rayon.putFloat("restitution", restitution);
        rayon.putBoolean("terrain_loading", terrainLoading);
        rayon.putString("buoyancy_type", buoyancyType.name().toLowerCase(Locale.ROOT));
        rayon.putString("drag_type", dragType.name().toLowerCase(Locale.ROOT));
    }

    public static Optional<PhysicsState> read(ValueInput input) {
        Optional<ValueInput> current = input.child("rayon");
        if (current.isPresent()) {
            return Optional.of(readVersion2(current.get()));
        }
        return readLegacy(input);
    }

    private static PhysicsState readVersion2(ValueInput input) {
        return new PhysicsState(
                QuaternionCodec.read(input, "orientation").orElseGet(Quaternion::new),
                Vector3fCodec.read(input, "linear_velocity").orElseGet(Vector3f::new),
                Vector3fCodec.read(input, "angular_velocity").orElseGet(Vector3f::new),
                input.getFloatOr("mass", 10.0f),
                input.getFloatOr("drag_coefficient", 0.25f),
                input.getFloatOr("friction", 1.0f),
                input.getFloatOr("restitution", 0.5f),
                input.getBooleanOr("terrain_loading", true),
                parseEnum(input.getStringOr("buoyancy_type", "water"), ElementRigidBody.BuoyancyType.class,
                        ElementRigidBody.BuoyancyType.WATER),
                parseEnum(input.getStringOr("drag_type", "simple"), ElementRigidBody.DragType.class,
                        ElementRigidBody.DragType.SIMPLE)
        );
    }

    private static Optional<PhysicsState> readLegacy(ValueInput input) {
        Optional<Quaternion> orientation = QuaternionCodec.read(input, "orientation");
        Optional<Vector3f> linearVelocity = Vector3fCodec.read(input, "linearVelocity");
        Optional<Vector3f> angularVelocity = Vector3fCodec.read(input, "angularVelocity");
        if (orientation.isEmpty() && linearVelocity.isEmpty() && angularVelocity.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new PhysicsState(
                orientation.orElseGet(Quaternion::new),
                linearVelocity.orElseGet(Vector3f::new),
                angularVelocity.orElseGet(Vector3f::new),
                input.getFloatOr("mass", 10.0f),
                input.getFloatOr("dragCoefficient", 0.25f),
                input.getFloatOr("friction", 1.0f),
                input.getFloatOr("restitution", 0.5f),
                input.getBooleanOr("terrainLoadingEnabled", true),
                enumByOrdinal(input.getIntOr("buoyancyType", ElementRigidBody.BuoyancyType.WATER.ordinal()),
                        ElementRigidBody.BuoyancyType.values(), ElementRigidBody.BuoyancyType.WATER),
                enumByOrdinal(input.getIntOr("dragType", ElementRigidBody.DragType.SIMPLE.ordinal()),
                        ElementRigidBody.DragType.values(), ElementRigidBody.DragType.SIMPLE)
        ));
    }

    public void apply(ElementRigidBody body) {
        body.setPhysicsRotation(orientation.clone());
        body.setLinearVelocity(linearVelocity.clone());
        body.setAngularVelocity(angularVelocity.clone());
        body.setMass(mass);
        body.setDragCoefficient(dragCoefficient);
        body.setFriction(friction);
        body.setRestitution(restitution);
        body.setTerrainLoadingEnabled(terrainLoading);
        body.setBuoyancyType(buoyancyType);
        body.setDragType(dragType);
        body.activate();
        body.refreshPersistenceSnapshot();
    }

    private static float finiteNonNegative(float value, float fallback) {
        return Float.isFinite(value) && value >= 0.0f ? value : fallback;
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> type, E fallback) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static <E> E enumByOrdinal(int ordinal, E[] values, E fallback) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }
}
