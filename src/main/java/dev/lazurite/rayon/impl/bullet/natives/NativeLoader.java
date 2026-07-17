package dev.lazurite.rayon.impl.bullet.natives;

import com.jme3.system.JmeSystem;
import com.jme3.system.NativeLibraryLoader;
import dev.lazurite.rayon.impl.Rayon;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Libbulletjme 네이티브를 해시 기반 캐시에 안전하게 준비한다. */
public final class NativeLoader {
    private static final String LIBRARY_VERSION = "17.4.0";

    private NativeLoader() {
    }

    public static void load() {
        java.util.logging.Logger.getLogger("com.jme3.system.NativeLibraryLoader")
                .setLevel(java.util.logging.Level.WARNING);
        var platform = JmeSystem.getPlatform();
        String fileName = switch (platform) {
            case Windows64 -> "Windows64ReleaseSp_bulletjme.dll";
            case Linux64 -> "Linux64ReleaseSp_libbulletjme.so";
            case MacOSX64 -> "MacOSX64ReleaseSp_libbulletjme.dylib";
            case MacOSX_ARM64 -> "MacOSX_ARM64ReleaseSp_libbulletjme.dylib";
            default -> throw new IllegalStateException("지원하지 않는 Rayon 서버 플랫폼입니다: " + platform);
        };

        byte[] nativeBytes = readResource("/assets/natives/" + fileName);
        String hash = sha256(nativeBytes);
        Path nativeDirectory = FabricLoader.getInstance().getGameDir()
                .resolve(".rayon")
                .resolve("natives")
                .resolve(LIBRARY_VERSION)
                .resolve(platform.toString())
                .resolve(hash);
        Path destination = nativeDirectory.resolve(fileName);

        try {
            Files.createDirectories(nativeDirectory);
            Path lockPath = nativeDirectory.resolve(".extract.lock");
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                if (!Files.isRegularFile(destination) || !sha256(Files.readAllBytes(destination)).equals(hash)) {
                    Path temporary = Files.createTempFile(nativeDirectory, fileName, ".tmp");
                    try {
                        Files.write(temporary, nativeBytes, StandardOpenOption.TRUNCATE_EXISTING);
                        try {
                            Files.move(temporary, destination,
                                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        } catch (java.nio.file.AtomicMoveNotSupportedException ignoredMove) {
                            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } finally {
                        Files.deleteIfExists(temporary);
                    }
                }
            }

            NativeLibraryLoader.loadLibbulletjme(true, nativeDirectory.toFile(), "Release", "Sp");
            Rayon.LOGGER.info("Libbulletjme {} 네이티브를 로드했습니다: {}", LIBRARY_VERSION, platform);
        } catch (IOException exception) {
            throw new IllegalStateException("Libbulletjme 네이티브 준비 또는 로드에 실패했습니다: " + destination, exception);
        }
    }

    private static byte[] readResource(String resourcePath) {
        try (InputStream input = NativeLoader.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("Rayon JAR에 필요한 네이티브가 없습니다: " + resourcePath);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Rayon 네이티브 리소스를 읽지 못했습니다: " + resourcePath, exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM에서 SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
