package dev.lazurite.rayon.impl.serialization;

import java.util.Optional;

public interface PhysicsStateHolder {
    void rayon$setPendingPhysicsState(PhysicsState state);

    Optional<PhysicsState> rayon$takePendingPhysicsState();
}
