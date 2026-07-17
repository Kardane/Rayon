package dev.lazurite.rayon;

import dev.lazurite.rayon.impl.Rayon;
import net.fabricmc.api.ModInitializer;

public final class RayonMod implements ModInitializer {
    @Override
    public void onInitialize() {
        Rayon.initialize();
    }
}
