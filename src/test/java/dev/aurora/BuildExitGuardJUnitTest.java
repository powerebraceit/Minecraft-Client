package dev.aurora;

import dev.aurora.core.builder.AStarPathfinder.*;
import dev.aurora.minecraft.builder.BuildExitGuard;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** A prospective doorway block must preserve a real route; no live world is mutated by the check. */
class BuildExitGuardJUnitTest {
    private static final Cell START = new Cell(0, 1, 0), EXIT = new Cell(4, 1, 0);
    private static final BlockPos DOORWAY = new BlockPos(2, 1, 0);
    private static final Bounds BOUNDS = new Bounds(-1, 0, -2, 5, 4, 3);
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static final class World implements BlockGetter {
        final Map<BlockPos, BlockState> states = new HashMap<>();
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public int getHeight() { return 384; }
        @Override public int getMinY() { return -64; }
        void solid(int x, int y, int z) { states.put(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState()); }
    }
    private static World corridor(int width) {
        var world = new World();
        for (int x = -1; x <= 5; x++) for (int z = -1; z <= width; z++) {
            world.solid(x, 0, z);world.solid(x, 3, z);
            if (z == -1 || z == width || x == -1 || x == 5) {
                world.solid(x, 1, z);world.solid(x, 2, z);
            }
        }
        return world;
    }
    @Test void closingTheOnlyLowDoorwayHasNoExecutableEscapePath() {
        var world = corridor(1);
        Result result = BuildExitGuard.evaluate(world, START, EXIT, DOORWAY,
            Blocks.STONE.defaultBlockState(), cell -> true, BOUNDS, 500);
        assertFalse(result.reached());assertTrue(result.path().isEmpty());
        assertTrue(world.getBlockState(DOORWAY).isAir(), "Safety simulation must not mutate the actual world");
    }
    @Test void anotherOpenPassageMakesThePlacementSafe() {
        var world = corridor(2);
        Result result = BuildExitGuard.evaluate(world, START, EXIT, DOORWAY,
            Blocks.STONE.defaultBlockState(), cell -> true, BOUNDS, 500);
        assertTrue(result.reached());assertEquals(Optional.of(EXIT), result.reachedGoal());
        assertTrue(result.path().stream().anyMatch(cell -> cell.z() == 1));
    }
    @Test void theLastDoorwayCanBeClosedFromTheExteriorSide() {
        var world = corridor(1);
        Result result = BuildExitGuard.evaluate(world, EXIT, EXIT, DOORWAY,
            Blocks.STONE.defaultBlockState(), cell -> true, BOUNDS, 500);
        assertTrue(result.reached());assertTrue(result.path().isEmpty());
    }
    @Test void anUnloadedEscapeIsNeverAcceptedAsProof() {
        var world = corridor(2);
        Result result = BuildExitGuard.evaluate(world, START, EXIT, DOORWAY,
            Blocks.STONE.defaultBlockState(), cell -> cell.x() < 3, BOUNDS, 500);
        assertFalse(result.reached());assertTrue(result.path().isEmpty());
    }
    @Test void nodeBudgetExhaustionDoesNotAllowThePlacement() {
        var world = corridor(2);
        Result result = BuildExitGuard.evaluate(world, START, EXIT, DOORWAY,
            Blocks.STONE.defaultBlockState(), cell -> true, BOUNDS, 1);
        assertEquals(Status.NODE_BUDGET_EXHAUSTED, result.status());assertTrue(result.path().isEmpty());
    }
}
