package dev.lazurite.rayon.impl.serialization;

import com.jme3.math.Vector3f;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Optional;

public final class Vector3fCodec {
    private Vector3fCodec() {
    }

    public static void write(ValueOutput output, String key, Vector3f vector) {
        Vector3f value = sanitize(vector);
        ValueOutput child = output.child(key);
        child.putFloat("x", value.x);
        child.putFloat("y", value.y);
        child.putFloat("z", value.z);
    }

    public static Optional<Vector3f> read(ValueInput input, String key) {
        return input.child(key).map(child -> sanitize(new Vector3f(
                child.getFloatOr("x", 0.0f),
                child.getFloatOr("y", 0.0f),
                child.getFloatOr("z", 0.0f)
        )));
    }

    public static Vector3f sanitize(Vector3f vector) {
        if (!Float.isFinite(vector.x) || !Float.isFinite(vector.y) || !Float.isFinite(vector.z)) {
            return new Vector3f();
        }
        return vector.clone();
    }
}
