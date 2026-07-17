package dev.lazurite.rayon.impl.serialization;

import com.jme3.math.Quaternion;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Optional;

public final class QuaternionCodec {
    private static final float MIN_NORM = 1.0e-8f;

    private QuaternionCodec() {
    }

    public static void write(ValueOutput output, String key, Quaternion quaternion) {
        Quaternion value = sanitize(quaternion);
        ValueOutput child = output.child(key);
        child.putFloat("x", value.getX());
        child.putFloat("y", value.getY());
        child.putFloat("z", value.getZ());
        child.putFloat("w", value.getW());
    }

    public static Optional<Quaternion> read(ValueInput input, String key) {
        return input.child(key).map(child -> sanitize(new Quaternion(
                child.getFloatOr("x", 0.0f),
                child.getFloatOr("y", 0.0f),
                child.getFloatOr("z", 0.0f),
                child.getFloatOr("w", 1.0f)
        )));
    }

    public static Quaternion sanitize(Quaternion quaternion) {
        if (!Float.isFinite(quaternion.getX())
                || !Float.isFinite(quaternion.getY())
                || !Float.isFinite(quaternion.getZ())
                || !Float.isFinite(quaternion.getW())
                || quaternion.norm() < MIN_NORM) {
            return new Quaternion();
        }
        return quaternion.clone().normalizeLocal();
    }
}
