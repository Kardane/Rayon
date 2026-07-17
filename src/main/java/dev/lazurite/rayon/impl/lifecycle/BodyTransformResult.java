package dev.lazurite.rayon.impl.lifecycle;

/** 물리 스레드에서 서버 스레드로 전달되는 Minecraft 객체 비포함 결과다. */
public record BodyTransformResult(
        int entityId,
        float x,
        float y,
        float z,
        float velocityX,
        float velocityY,
        float velocityZ
) {
}
