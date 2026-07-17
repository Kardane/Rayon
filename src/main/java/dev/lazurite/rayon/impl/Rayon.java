package dev.lazurite.rayon.impl;

import dev.lazurite.rayon.impl.event.ServerEventHandler;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class Rayon {
    public static final String MOD_ID = "rayon";
    public static final Logger LOGGER = LogManager.getLogger("Rayon");

    private static boolean initialized;

    private Rayon() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }

        initialized = true;
        ServerEventHandler.register();
        LOGGER.info("Rayon 2 서버 물리 API를 초기화했습니다.");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
