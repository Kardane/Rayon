package dev.lazurite.rayon.impl.event;

import dev.lazurite.rayon.api.EntityPhysicsElement;
import dev.lazurite.rayon.api.event.collision.PhysicsSpaceEvents;
import dev.lazurite.rayon.impl.Rayon;
import dev.lazurite.rayon.impl.bullet.collision.body.EntityRigidBody;
import dev.lazurite.rayon.impl.bullet.collision.space.MinecraftSpace;
import dev.lazurite.rayon.impl.bullet.collision.space.generator.EntityCollisionGenerator;
import dev.lazurite.rayon.impl.bullet.collision.space.generator.PressureGenerator;
import dev.lazurite.rayon.impl.bullet.collision.space.generator.TerrainGenerator;
import dev.lazurite.rayon.impl.bullet.math.Convert;
import dev.lazurite.rayon.impl.lifecycle.RayonServerRuntime;
import dev.lazurite.rayon.impl.serialization.PhysicsStateHolder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

public final class ServerEventHandler {
    private static boolean registered;

    private ServerEventHandler() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;

        PhysicsSpaceEvents.STEP.register(PressureGenerator::step);
        PhysicsSpaceEvents.STEP.register(TerrainGenerator::step);

        ServerLifecycleEvents.SERVER_STARTING.register(RayonServerRuntime::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
                RayonServerRuntime.find(server).ifPresent(RayonServerRuntime::close));

        ServerWorldEvents.LOAD.register((server, level) ->
                RayonServerRuntime.get(server).createSpace(level));
        ServerWorldEvents.UNLOAD.register((server, level) ->
                RayonServerRuntime.find(server).ifPresent(runtime -> runtime.removeSpace(level)));
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
                MinecraftSpace.getOptional(level).ifPresent(space ->
                        space.evictChunk(chunk.getPos().x, chunk.getPos().z)));

        ServerEntityEvents.ENTITY_LOAD.register(ServerEventHandler::onEntityLoad);
        ServerEntityEvents.ENTITY_UNLOAD.register(ServerEventHandler::onEntityUnload);

        ServerTickEvents.START_WORLD_TICK.register(ServerEventHandler::onWorldTick);
        ServerTickEvents.END_SERVER_TICK.register(server ->
                RayonServerRuntime.find(server).ifPresent(RayonServerRuntime::throwIfFailed));
    }

    private static void onWorldTick(ServerLevel level) {
        MinecraftSpace.getOptional(level).ifPresent(space -> {
            space.applyCompletedResults();
            EntityCollisionGenerator.step(space);
            space.prepareTick();
            space.requestStep();
        });
    }

    private static void onEntityLoad(Entity entity, ServerLevel level) {
        if (!EntityPhysicsElement.is(entity)) {
            return;
        }

        MinecraftSpace.getOptional(level).ifPresent(space -> {
            EntityRigidBody rigidBody = EntityPhysicsElement.get(entity).getRigidBody();
            var initialPosition = Convert.toBullet(entity.position());
            var pendingState = entity instanceof PhysicsStateHolder holder
                    ? holder.rayon$takePendingPhysicsState()
                    : java.util.Optional.<dev.lazurite.rayon.impl.serialization.PhysicsState>empty();
            space.getExecutor().execute(() -> {
                rigidBody.setPhysicsLocation(initialPosition);
                pendingState.ifPresent(state -> state.apply(rigidBody));
                space.addCollisionObject(rigidBody);
            });
        });
    }

    private static void onEntityUnload(Entity entity, ServerLevel level) {
        if (!EntityPhysicsElement.is(entity)) {
            return;
        }

        MinecraftSpace.getOptional(level).ifPresent(space -> {
            EntityRigidBody rigidBody = EntityPhysicsElement.get(entity).getRigidBody();
            space.getExecutor().execute(() -> space.removeCollisionObject(rigidBody));
        });
    }

    public static void onBlockUpdate(ServerLevel level, BlockPos blockPos) {
        MinecraftSpace.getOptional(level).ifPresent(space -> space.doBlockUpdate(blockPos.immutable()));
    }
}
