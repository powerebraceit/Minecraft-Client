package dev.aurora;

import dev.aurora.core.builder.AStarPathfinder.Cell;
import dev.aurora.minecraft.builder.TerrainSafety;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainSafetyJUnitTest {
    private static final Cell ORIGIN=new Cell(0,0,0);
    private static final Vec3 FEET=new Vec3(0.5,0,0.5);
    @BeforeAll static void bootstrap() {SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    private static SchematicPreviewJUnitTest.World floor() {
        var world=new SchematicPreviewJUnitTest.World();
        world.states.put(BlockPos.ZERO.below(),Blocks.STONE.defaultBlockState());
        return world;
    }

    @Test void realFootSupportIsRequiredEvenWhenTheViewpointWouldHaveReach() {
        var world=floor();
        assertTrue(TerrainSafety.canStand(world,ORIGIN));
        assertTrue(TerrainSafety.canStandAt(world,FEET,0.6,1.8));
        assertFalse(TerrainSafety.canStandAt(world,FEET.add(0,0.4,0),0.6,1.8));
        world.states.clear();
        assertFalse(TerrainSafety.canStand(world,ORIGIN));
        assertFalse(TerrainSafety.canStandAt(world,FEET,0.6,1.8));
    }

    @Test void lavaFireAndDangerousFloorsAreNeverStandingGoals() {
        for(var block:java.util.List.of(Blocks.LAVA,Blocks.FIRE,Blocks.SOUL_FIRE,Blocks.POWDER_SNOW,Blocks.SWEET_BERRY_BUSH)) {
            var world=floor();world.states.put(BlockPos.ZERO,block.defaultBlockState());
            assertFalse(TerrainSafety.canStand(world,ORIGIN),block.toString());
            assertFalse(TerrainSafety.canStandAt(world,FEET,0.6,1.8),block.toString());
        }
        for(var block:java.util.List.of(Blocks.MAGMA_BLOCK,Blocks.CACTUS,Blocks.CAMPFIRE,Blocks.SOUL_CAMPFIRE)) {
            var world=floor();world.states.put(BlockPos.ZERO.below(),block.defaultBlockState());
            assertFalse(TerrainSafety.canStand(world,ORIGIN),block.toString());
            assertFalse(TerrainSafety.canStandAt(world,FEET,0.6,1.8),block.toString());
        }
    }

    @Test void actualPartialBlockStanceIsSafeWithoutRoundingItToAir() {
        var world=new SchematicPreviewJUnitTest.World();
        world.states.put(BlockPos.ZERO,Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM));
        assertFalse(TerrainSafety.canStand(world,ORIGIN));
        assertTrue(TerrainSafety.canStandAt(world,FEET.add(0,0.5,0),0.6,1.8));
        world.states.put(BlockPos.ZERO,Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS,3));
        // Snow collision is one layer lower than its outline; stand on the actual collision top.
        double height=world.getBlockState(BlockPos.ZERO).getCollisionShape(world,BlockPos.ZERO).max(net.minecraft.core.Direction.Axis.Y);
        assertTrue(TerrainSafety.canStandAt(world,FEET.add(0,height,0),0.6,1.8));
    }

    @Test void tallCollisionShapesBelowTheFootCellAreStillBodyObstacles() {
        var world=new SchematicPreviewJUnitTest.World();world.states.put(BlockPos.ZERO,Blocks.OAK_FENCE.defaultBlockState());
        assertFalse(TerrainSafety.canStandAt(world,FEET.add(0,1.25,0),0.6,1.8));
        assertTrue(TerrainSafety.canStandAt(world,FEET.add(0,1.5,0),0.6,1.8));
    }

    @Test void headroomAndFluidInsideAPartialStandingBlockAreChecked() {
        var world=floor();world.states.put(BlockPos.ZERO.above(),Blocks.STONE.defaultBlockState());
        assertFalse(TerrainSafety.canStand(world,ORIGIN));
        assertFalse(TerrainSafety.canStandAt(world,FEET,0.6,1.8));
        world.states.clear();
        world.states.put(BlockPos.ZERO,Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM).setValue(BlockStateProperties.WATERLOGGED,true));
        assertFalse(TerrainSafety.canStandAt(world,FEET.add(0,0.5,0),0.6,1.8));
    }

    @Test void worldMutationsImmediatelyChangeSafetyRatherThanUsingAStaleCache() {
        var world=floor();assertTrue(TerrainSafety.canStand(world,ORIGIN));
        world.states.put(BlockPos.ZERO,Blocks.STONE.defaultBlockState());assertFalse(TerrainSafety.canStand(world,ORIGIN));
        world.states.remove(BlockPos.ZERO);assertTrue(TerrainSafety.canStand(world,ORIGIN));
        world.states.remove(BlockPos.ZERO.below());assertFalse(TerrainSafety.canStand(world,ORIGIN));
    }
}
