package dev.lazurite.rayon.impl.util;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;

/** 물리 스레드 내부의 이전/현재 변환 상태다. */
public final class Frame {
    private Vector3f previousLocation;
    private Vector3f currentLocation;
    private Quaternion previousRotation;
    private Quaternion currentRotation;

    public Frame() {
        this(new Vector3f(), new Quaternion());
    }

    public Frame(Vector3f location, Quaternion rotation) {
        set(location, location, rotation, rotation);
    }

    public void set(Vector3f previousLocation, Vector3f currentLocation,
                    Quaternion previousRotation, Quaternion currentRotation) {
        this.previousLocation = previousLocation.clone();
        this.currentLocation = currentLocation.clone();
        this.previousRotation = previousRotation.clone();
        this.currentRotation = currentRotation.clone();
    }

    public void from(Frame previousFrame, Vector3f location, Quaternion rotation) {
        set(previousFrame.currentLocation, location, previousFrame.currentRotation, rotation);
    }

    public Vector3f getLocation(Vector3f store, float tickDelta) {
        float inverse = 1.0f - tickDelta;
        return store.set(
                previousLocation.x * inverse + currentLocation.x * tickDelta,
                previousLocation.y * inverse + currentLocation.y * tickDelta,
                previousLocation.z * inverse + currentLocation.z * tickDelta
        );
    }

    public Quaternion getRotation(Quaternion store, float tickDelta) {
        float x1 = previousRotation.getX();
        float y1 = previousRotation.getY();
        float z1 = previousRotation.getZ();
        float w1 = previousRotation.getW();
        float x2 = currentRotation.getX();
        float y2 = currentRotation.getY();
        float z2 = currentRotation.getZ();
        float w2 = currentRotation.getW();
        float dot = x1 * x2 + y1 * y2 + z1 * z2 + w1 * w2;
        if (dot < 0.0f) {
            x2 = -x2;
            y2 = -y2;
            z2 = -z2;
            w2 = -w2;
        }
        float inverse = 1.0f - tickDelta;
        return store.set(
                x1 * inverse + x2 * tickDelta,
                y1 * inverse + y2 * tickDelta,
                z1 * inverse + z2 * tickDelta,
                w1 * inverse + w2 * tickDelta
        ).normalizeLocal();
    }

    public Vector3f getLocationDelta(Vector3f store) {
        return store.set(currentLocation).subtractLocal(previousLocation);
    }

}
