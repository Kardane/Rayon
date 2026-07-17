package dev.lazurite.rayon.impl.bullet.collision.space.cache;

import dev.lazurite.rayon.impl.Rayon;

import java.util.Locale;

/** 서버 재시작 시 선택되는 지형 처리 모드다. */
public enum TerrainMode {
    LEGACY("legacy"),
    SECTION_CACHE("section_cache");

    private static final TerrainMode CONFIGURED = readConfiguredMode();
    private final String propertyValue;

    TerrainMode(String propertyValue) {
        this.propertyValue = propertyValue;
    }

    public static TerrainMode configured() {
        return CONFIGURED;
    }

    public boolean usesSectionCache() {
        return this == SECTION_CACHE;
    }

    public String propertyValue() {
        return propertyValue;
    }

    private static TerrainMode readConfiguredMode() {
        String configured = System.getProperty("rayon.terrain.mode", SECTION_CACHE.propertyValue)
                .trim()
                .toLowerCase(Locale.ROOT);
        for (TerrainMode mode : values()) {
            if (mode.propertyValue.equals(configured)) {
                return mode;
            }
        }

        Rayon.LOGGER.warn("알 수 없는 rayon.terrain.mode={} 값입니다. section_cache를 사용합니다.", configured);
        return SECTION_CACHE;
    }
}
