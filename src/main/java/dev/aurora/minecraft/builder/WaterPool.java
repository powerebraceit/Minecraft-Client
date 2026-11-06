package dev.aurora.minecraft.builder;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.*;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;

/** Four source cells, a solid floor and containment before any water is poured. Never excavates terrain. */
public final class WaterPool {
    private WaterPool() { }
    public static List<BlockPos> cells(BlockPos corner) {return List.of(corner,corner.east(),corner.south(),corner.south().east());}
    public static boolean source(BlockGetter world,BlockPos pos) {var state=world.getBlockState(pos);return state.is(Blocks.WATER) && state.getFluidState().isSource();}
    public static boolean ready(BlockGetter world,BlockPos corner) {return cells(corner).stream().allMatch(p->source(world,p));}
    public static boolean safe(BlockGetter world,BlockPos corner,Predicate<BlockPos> allowed) {
        var cells=cells(corner);boolean ready=ready(world,corner);
        for(var pos:cells) {
            if(!allowed.test(pos) || !world.getBlockState(pos.below()).isSolid() || !world.getBlockState(pos.above()).isAir())return false;
            var state=world.getBlockState(pos);if(!state.isAir() && !state.is(Blocks.WATER))return false;
            if(!ready)for(Direction side:List.of(Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST)) {
                BlockPos neighbor=pos.relative(side);
                var wall=world.getBlockState(neighbor);
                if(!cells.contains(neighbor) && (!wall.isFaceSturdy(world,neighbor,side.getOpposite()) || wall.getBlock() instanceof net.minecraft.world.level.block.LiquidBlockContainer))return false;
            }
        }
        return true;
    }
}
