package dev.lazurite.rayon.impl.lifecycle;

import dev.lazurite.rayon.api.event.collision.PhysicsSpaceEvents;
import dev.lazurite.rayon.impl.Rayon;
import dev.lazurite.rayon.impl.bullet.collision.space.MinecraftSpace;
import dev.lazurite.rayon.impl.bullet.natives.NativeLoader;
import dev.lazurite.rayon.impl.bullet.thread.PhysicsExecutor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class RayonServerRuntime implements AutoCloseable {
    private static final Map<MinecraftServer, RayonServerRuntime> RUNTIMES = new IdentityHashMap<>();

    private final MinecraftServer server;
    private final PhysicsExecutor executor;
    private final Map<ResourceKey<Level>, MinecraftSpace> spaces = new ConcurrentHashMap<>();

    private RayonServerRuntime(MinecraftServer server) {
        this.server = server;
        this.executor = new PhysicsExecutor();
    }

    public static synchronized RayonServerRuntime start(MinecraftServer server) {
        if (RUNTIMES.containsKey(server)) {
            throw new IllegalStateException("Rayon 서버 런타임이 이미 시작되었습니다.");
        }

        NativeLoader.load();
        RayonServerRuntime runtime = new RayonServerRuntime(server);
        RUNTIMES.put(server, runtime);
        Rayon.LOGGER.info("Rayon 서버 물리 런타임을 시작했습니다.");
        return runtime;
    }

    public static synchronized Optional<RayonServerRuntime> find(MinecraftServer server) {
        return Optional.ofNullable(RUNTIMES.get(server));
    }

    public static RayonServerRuntime get(MinecraftServer server) {
        return find(server).orElseThrow(() -> new IllegalStateException("Rayon 서버 런타임이 시작되지 않았습니다."));
    }

    public static RayonServerRuntime get(ServerLevel level) {
        return get(level.getServer());
    }

    public MinecraftSpace createSpace(ServerLevel level) {
        String levelName = level.dimension().location().toString();
        MinecraftSpace space = executor.call(() -> new MinecraftSpace(executor, level, levelName));
        MinecraftSpace previous = spaces.putIfAbsent(level.dimension(), space);
        if (previous != null) {
            executor.execute(space::destroySpace);
            return previous;
        }

        PhysicsSpaceEvents.INIT.invoker().onInit(space);
        return space;
    }

    public Optional<MinecraftSpace> findSpace(ServerLevel level) {
        return Optional.ofNullable(spaces.get(level.dimension()));
    }

    public MinecraftSpace getSpace(ServerLevel level) {
        return findSpace(level).orElseThrow(() -> new IllegalStateException("월드 물리 공간이 아직 생성되지 않았습니다: " + level.dimension().location()));
    }

    public void removeSpace(ServerLevel level) {
        MinecraftSpace space = spaces.remove(level.dimension());
        if (space != null) {
            executor.execute(space::destroySpace);
        }
    }

    public PhysicsExecutor executor() {
        return executor;
    }

    public MinecraftServer server() {
        return server;
    }

    public void throwIfFailed() {
        executor.throwIfFailed();
    }

    @Override
    public void close() {
        spaces.values().forEach(space -> executor.execute(space::destroySpace));
        spaces.clear();
        executor.close();
        synchronized (RayonServerRuntime.class) {
            RUNTIMES.remove(server);
        }
        Rayon.LOGGER.info("Rayon 서버 물리 런타임을 종료했습니다.");
    }
}
