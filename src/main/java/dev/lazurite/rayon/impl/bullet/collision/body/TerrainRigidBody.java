package dev.lazurite.rayon.impl.bullet.collision.body;

import com.jme3.math.Vector3f;
import dev.lazurite.rayon.impl.bullet.collision.body.shape.MinecraftShape;
import dev.lazurite.rayon.impl.bullet.collision.body.shape.Triangle;
import dev.lazurite.rayon.impl.bullet.collision.space.MinecraftSpace;
import dev.lazurite.rayon.impl.bullet.collision.space.block.BlockProperty;
import dev.lazurite.rayon.impl.bullet.collision.space.cache.ChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

public class TerrainRigidBody extends MinecraftRigidBody {
    private final BlockPos blockPos;
    private final BlockState state;
    private final List<AABB> collisionBoxes;

    public static TerrainRigidBody from(MinecraftSpace space, ChunkCache.BlockData blockData) {
        final var blockProperty = BlockProperty.getBlockProperty(blockData.blockState().getBlock());
        final var friction = blockProperty == null ? 0.75f : blockProperty.friction();
        final var restitution = blockProperty == null ? 0.25f : blockProperty.restitution();
        var shape = MinecraftShape.concave(Triangle.getMeshOfBoxes(blockData.collisionBoxes()));
        return new TerrainRigidBody(space, shape, blockData.blockPos(), blockData.blockState(),
                blockData.collisionBoxes(), friction, restitution);
    }

    public TerrainRigidBody(MinecraftSpace space, MinecraftShape shape, BlockPos blockPos, BlockState blockState, float friction, float restitution) {
        this(space, shape, blockPos, blockState, List.of(), friction, restitution);
    }

    private TerrainRigidBody(MinecraftSpace space, MinecraftShape shape, BlockPos blockPos, BlockState blockState,
                             List<AABB> collisionBoxes, float friction, float restitution) {
        super(space, shape);
        this.blockPos = blockPos;
        this.state = blockState;
        this.collisionBoxes = List.copyOf(collisionBoxes);

        this.setFriction(friction);
        this.setRestitution(restitution);
        this.setPhysicsLocation(new Vector3f(blockPos.getX() + 0.5f, blockPos.getY() + 0.5f, blockPos.getZ() + 0.5f));
    }

    public BlockPos getBlockPos() {
        return this.blockPos;
    }

    public BlockState getBlockState() {
        return this.state;
    }

    public boolean matches(ChunkCache.BlockData blockData) {
        return state == blockData.blockState() && collisionBoxes.equals(blockData.collisionBoxes());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof TerrainRigidBody terrain) {
            return terrain.getBlockPos().equals(this.blockPos) && terrain.getBlockState().equals(this.state);
        }

        return false;
    }

    @Override
    public Vector3f getOutlineColor() {
        return new Vector3f(0.25f, 0.25f, 1.0f);
    }
}
