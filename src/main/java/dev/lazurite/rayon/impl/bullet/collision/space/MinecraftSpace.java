package dev.lazurite.rayon.impl.bullet.collision.space;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.PhysicsCollisionEvent;
import com.jme3.bullet.collision.PhysicsCollisionListener;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import dev.lazurite.rayon.api.event.collision.ElementCollisionEvents;
import dev.lazurite.rayon.api.event.collision.PhysicsSpaceEvents;
import dev.lazurite.rayon.impl.Rayon;
import dev.lazurite.rayon.impl.bullet.collision.body.ElementRigidBody;
import dev.lazurite.rayon.impl.bullet.collision.body.EntityRigidBody;
import dev.lazurite.rayon.impl.bullet.collision.body.TerrainRigidBody;
import dev.lazurite.rayon.impl.bullet.collision.space.cache.ChunkCache;
import dev.lazurite.rayon.impl.bullet.thread.PhysicsExecutor;
import dev.lazurite.rayon.impl.lifecycle.BodyTransformResult;
import dev.lazurite.rayon.impl.lifecycle.RayonServerRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 서버 월드 하나에 대응하는 Bullet 물리 공간이다. */
@SuppressWarnings("deprecation") // Libbulletjme 17.4.0의 collision listener API를 ABI 호환 기준으로 유지한다.
public final class MinecraftSpace extends PhysicsSpace implements PhysicsCollisionListener {
    private static final long SLOW_STEP_NANOS = 50_000_000L;

    private final Map<BlockPos, TerrainRigidBody> terrainMap = new ConcurrentHashMap<>();
    private final List<ElementRigidBody> elementBodies = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<SectionPos> blockUpdates = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<BodyTransformResult> completedResults = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean stepQueued = new AtomicBoolean();
    private final PhysicsExecutor executor;
    private final ServerLevel level;
    private final String levelName;
    private final ChunkCache chunkCache;
    private volatile boolean destroyed;

    public static MinecraftSpace get(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalArgumentException("Rayon 2는 서버 월드만 지원합니다.");
        }
        return RayonServerRuntime.get(serverLevel).getSpace(serverLevel);
    }

    public static Optional<MinecraftSpace> getOptional(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return Optional.empty();
        }
        return RayonServerRuntime.find(serverLevel.getServer()).flatMap(runtime -> runtime.findSpace(serverLevel));
    }

    public MinecraftSpace(PhysicsExecutor executor, ServerLevel level, String levelName) {
        super(BroadphaseType.DBVT);
        this.executor = executor;
        this.level = level;
        this.levelName = levelName;
        this.chunkCache = ChunkCache.create(this);
        setGravity(new Vector3f(0, -9.807f, 0));
        addCollisionListener(this);
        setAccuracy(1f / 60f);
    }

    /** 서버 스레드에서 다음 물리 작업이 소비할 지형 snapshot을 갱신한다. */
    public void prepareTick() {
        if (!destroyed) {
            chunkCache.refreshAll();
        }
    }

    /** 이전 작업이 끝나지 않았으면 요청을 병합하여 무제한 backlog를 방지한다. */
    public void requestStep() {
        if (destroyed || !stepQueued.compareAndSet(false, true)) {
            return;
        }

        try {
            executor.execute(this::runStep);
        } catch (RejectedExecutionException exception) {
            stepQueued.set(false);
            if (!destroyed) {
                throw exception;
            }
        }
    }

    private void runStep() {
        long started = System.nanoTime();
        try {
            if (destroyed) {
                return;
            }

            wakeBodiesNearChangedSections();
            if (!isEmpty()) {
                for (int substep = 0; substep < 3; substep++) {
                    distributeEvents();
                    PhysicsSpaceEvents.STEP.invoker().onStep(this);
                    update(1f / 60f);
                }
            }

            for (ElementRigidBody rigidBody : elementBodies) {
                rigidBody.updateFrame();
                if (rigidBody instanceof EntityRigidBody entityBody) {
                    Vector3f location = entityBody.getPhysicsLocation(new Vector3f());
                    Vector3f velocity = entityBody.getLinearVelocity(new Vector3f());
                    completedResults.add(new BodyTransformResult(
                            entityBody.getEntityId(),
                            location.x, location.y, location.z,
                            velocity.x, velocity.y, velocity.z
                    ));
                }
            }
        } finally {
            stepQueued.set(false);
            long elapsed = System.nanoTime() - started;
            if (elapsed > SLOW_STEP_NANOS) {
                Rayon.LOGGER.warn("{} 월드의 물리 step이 {} ms 걸렸습니다.", levelName, elapsed / 1_000_000.0);
            }
        }
    }

    private void wakeBodiesNearChangedSections() {
        SectionPos section;
        while ((section = blockUpdates.poll()) != null) {
            for (ElementRigidBody rigidBody : elementBodies) {
                if (rigidBody.terrainLoadingEnabled() && rigidBody.isNear(section)) {
                    rigidBody.activate();
                }
            }
        }
    }

    /** 서버 스레드에서만 호출한다. */
    public void applyCompletedResults() {
        BodyTransformResult result;
        while ((result = completedResults.poll()) != null) {
            var entity = level.getEntity(result.entityId());
            if (entity == null || entity.isRemoved()) {
                continue;
            }
            entity.setPos(result.x(), result.y(), result.z());
            entity.setDeltaMovement(new Vec3(result.velocityX(), result.velocityY(), result.velocityZ()));
        }
    }

    @Override
    public void addCollisionObject(PhysicsCollisionObject collisionObject) {
        if (destroyed || collisionObject.isInWorld()) {
            return;
        }

        if (collisionObject instanceof ElementRigidBody rigidBody) {
            PhysicsSpaceEvents.ELEMENT_ADDED.invoker().onElementAdded(this, rigidBody);
            rigidBody.activate();
            rigidBody.getFrame().set(
                    rigidBody.getPhysicsLocation(new Vector3f()),
                    rigidBody.getPhysicsLocation(new Vector3f()),
                    rigidBody.getPhysicsRotation(new Quaternion()),
                    rigidBody.getPhysicsRotation(new Quaternion())
            );
            rigidBody.updateBoundingBox();
            elementBodies.add(rigidBody);
        } else if (collisionObject instanceof TerrainRigidBody terrain) {
            terrainMap.put(terrain.getBlockPos(), terrain);
        }

        super.addCollisionObject(collisionObject);
    }

    @Override
    public void removeCollisionObject(PhysicsCollisionObject collisionObject) {
        if (!collisionObject.isInWorld()) {
            return;
        }

        super.removeCollisionObject(collisionObject);
        if (collisionObject instanceof ElementRigidBody rigidBody) {
            elementBodies.remove(rigidBody);
            PhysicsSpaceEvents.ELEMENT_REMOVED.invoker().onElementRemoved(this, rigidBody);
        } else if (collisionObject instanceof TerrainRigidBody terrain) {
            terrainMap.remove(terrain.getBlockPos(), terrain);
        }
    }

    public void doBlockUpdate(BlockPos blockPos) {
        blockUpdates.add(SectionPos.of(blockPos));
    }

    /** 서버 스레드에서 cache snapshot을 제거하고, 물리 스레드에서 Bullet terrain을 해제한다. */
    public void evictChunk(int chunkX, int chunkZ) {
        chunkCache.evictChunk(chunkX, chunkZ);
        executor.execute(() -> {
            for (var entry : getTerrainMap().entrySet()) {
                BlockPos pos = entry.getKey();
                if ((pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ) {
                    removeCollisionObject(entry.getValue());
                }
            }
        });
    }

    public List<ElementRigidBody> getElementBodiesSnapshot() {
        return List.copyOf(elementBodies);
    }

    public Map<BlockPos, TerrainRigidBody> getTerrainMap() {
        return new HashMap<>(terrainMap);
    }

    public Optional<TerrainRigidBody> getTerrainObjectAt(BlockPos blockPos) {
        return Optional.ofNullable(terrainMap.get(blockPos));
    }

    public void removeTerrainObjectAt(BlockPos blockPos) {
        TerrainRigidBody terrain = terrainMap.get(blockPos);
        if (terrain != null) {
            removeCollisionObject(terrain);
        }
    }

    public <T> List<T> getRigidBodiesByClass(Class<T> type) {
        List<T> output = new ArrayList<>();
        for (var body : getRigidBodyList()) {
            if (type.isInstance(body)) {
                output.add(type.cast(body));
            }
        }
        return output;
    }

    public PhysicsExecutor getExecutor() {
        return executor;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public ChunkCache getChunkCache() {
        return chunkCache;
    }

    public void destroySpace() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        for (var collisionObject : List.copyOf(getRigidBodyList())) {
            removeCollisionObject(collisionObject);
        }
        completedResults.clear();
        chunkCache.clear();
        terrainMap.clear();
        elementBodies.clear();
        destroy();
    }

    @Override
    public void collision(PhysicsCollisionEvent event) {
        float impulse = event.getAppliedImpulse();
        if (event.getObjectA() instanceof ElementRigidBody first && event.getObjectB() instanceof ElementRigidBody second) {
            ElementCollisionEvents.ELEMENT_COLLISION.invoker().onCollide(first.getElement(), second.getElement(), impulse);
        } else if (event.getObjectA() instanceof TerrainRigidBody terrain && event.getObjectB() instanceof ElementRigidBody body) {
            ElementCollisionEvents.BLOCK_COLLISION.invoker().onCollide(body.getElement(), terrain, impulse);
        } else if (event.getObjectA() instanceof ElementRigidBody body && event.getObjectB() instanceof TerrainRigidBody terrain) {
            ElementCollisionEvents.BLOCK_COLLISION.invoker().onCollide(body.getElement(), terrain, impulse);
        }
    }
}
