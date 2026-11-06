package dev.aurora.minecraft.builder;

import dev.aurora.core.builder.AStarPathfinder.Cell;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Shared terrain rules for navigation and simulated post-placement escape checks.
 * Callers supply loaded-chunk/world-border checks because a BlockGetter can also be a schematic overlay.
 */
public final class TerrainSafety {
    private static final double EPSILON=0.0001;

    private TerrainSafety() { }

    /** An integer-grid standing cell has room for feet and head and a stable full top face below it. */
    public static boolean canStand(BlockGetter world,Cell feet) {
        BlockPos pos=pos(feet);
        BlockState floor=world.getBlockState(pos.below());
        return isClear(world,feet) && isClear(world,feet.offset(0,1,0))
            && !hazardous(floor) && floor.isFaceSturdy(world,pos.below(),Direction.UP);
    }

    public static boolean isClear(BlockGetter world,Cell cell) {
        BlockPos pos=pos(cell);
        BlockState state=world.getBlockState(pos);
        return state.getCollisionShape(world,pos).isEmpty() && state.getFluidState().isEmpty() && !hazardous(state);
    }

    /**
     * Validate the real stance rather than rounding partial blocks up/down to a fictitious safe cell.
     * A thin support probe prevents an airborne position from becoming a valid placement viewpoint.
     */
    public static boolean canStandAt(BlockGetter world,Vec3 feet,double width,double height) {
        double half=width/2-EPSILON;
        AABB body=new AABB(feet.x-half,feet.y+EPSILON,feet.z-half,
            feet.x+half,feet.y+height-EPSILON,feet.z+half);
        AABB support=new AABB(feet.x-half,feet.y-0.075,feet.z-half,
            feet.x+half,feet.y-EPSILON,feet.z+half);
        return !unsafe(world,body) && !unsafe(world,support)
            && !collides(world,body) && collides(world,support);
    }

    /** Collision-only transition check; unlike a standing goal, a jumping body need not have a floor. */
    public static boolean bodyClearAt(BlockGetter world,Vec3 feet,double width,double height) {
        double half=width/2-EPSILON;
        AABB body=new AABB(feet.x-half,feet.y+EPSILON,feet.z-half,
            feet.x+half,feet.y+height-EPSILON,feet.z+half);
        return !unsafe(world,body) && !collides(world,body);
    }

    public static boolean hazardous(BlockState state) {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.LAVA)
            || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)
            || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
            || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.COBWEB);
    }

    private static boolean unsafe(BlockGetter world,AABB area) {
        for(BlockPos pos:BlockPos.betweenClosed(Mth.floor(area.minX),Mth.floor(area.minY),Mth.floor(area.minZ),
            Mth.floor(area.maxX-EPSILON),Mth.floor(area.maxY-EPSILON),Mth.floor(area.maxZ-EPSILON))) {
            BlockState state=world.getBlockState(pos);
            if(hazardous(state))return true;
            var fluid=state.getFluidState();
            if(!fluid.isEmpty() && area.minY<pos.getY()+fluid.getOwnHeight())return true;
        }
        return false;
    }

    private static boolean collides(BlockGetter world,AABB area) {
        for(BlockPos pos:BlockPos.betweenClosed(Mth.floor(area.minX),Mth.floor(area.minY)-1,Mth.floor(area.minZ),
            Mth.floor(area.maxX-EPSILON),Mth.floor(area.maxY-EPSILON),Mth.floor(area.maxZ-EPSILON)))
            for(var box:world.getBlockState(pos).getCollisionShape(world,pos).toAabbs())
                if(box.move(pos).intersects(area))return true;
        return false;
    }

    private static BlockPos pos(Cell cell) {return new BlockPos(cell.x(),cell.y(),cell.z());}
}
