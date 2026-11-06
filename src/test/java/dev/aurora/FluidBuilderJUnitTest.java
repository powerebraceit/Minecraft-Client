package dev.aurora;

import dev.aurora.core.builder.*;
import dev.aurora.minecraft.builder.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class FluidBuilderJUnitTest {
    @BeforeAll static void bootstrap() {SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    private static DirectSchematic schematic() {
        var blocks=new HashMap<net.minecraft.core.BlockPos,net.minecraft.world.level.block.state.BlockState>();
        for(int x=0;x<5;x++)blocks.put(new BlockPos(x,0,0),Blocks.WATER.defaultBlockState());
        for(int x=6;x<9;x++)blocks.put(new BlockPos(x,0,0),Blocks.LAVA.defaultBlockState());
        blocks.put(new BlockPos(0,0,1),Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL,1));
        blocks.put(new BlockPos(1,0,1),Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL,3));
        blocks.put(new BlockPos(0,4,0),Blocks.STONE.defaultBlockState());
        return new DirectSchematic("fluids",Path.of("fluids.litematic"),blocks);
    }
    @Test void waterCappedAtTwoLavaCountsEverySourceAndFlowingCellsCostNoBuckets() {
        var doc=schematic();var snapshot=doc.at(BlockPos.ZERO);
        assertEquals(9,snapshot.plan().blocks().size());
        assertEquals(Map.of("minecraft:water_bucket",2,"minecraft:lava_bucket",3,"minecraft:stone",1),BuildPlan.materials(snapshot.plan().blocks()));
        var rows=new SchematicMaterials(doc,BlockPos.ZERO).rows();
        assertEquals(2,rows.stream().filter(r->r.item().equals("minecraft:water_bucket")).findFirst().orElseThrow().total());
        assertEquals(3,rows.stream().filter(r->r.item().equals("minecraft:lava_bucket")).findFirst().orElseThrow().total());
        assertTrue(rows.stream().noneMatch(SchematicMaterials.Row::manual));
    }
    @Test void allSolidsAreScheduledBeforeFluidsRegardlessOfHeight() {
        var plan=schematic().at(BlockPos.ZERO).plan();
        assertEquals("minecraft:stone",plan.blocks().getFirst().item());
        assertEquals(4,plan.blocks().getFirst().y());
        assertTrue(plan.blocks().subList(1,plan.blocks().size()).stream().allMatch(BuildPlan::fluid));
    }
    @Test void placingSourcesUpdatesTheBucketChecklist() {
        var doc=schematic();var world=new SchematicPreviewJUnitTest.World();var materials=new SchematicMaterials(doc,BlockPos.ZERO);
        world.states.putAll(doc.blocks());materials.tick(world);
        assertEquals(0,materials.remainingItems());
        world.states.remove(new BlockPos(0,0,0));materials.changed(new BlockPos(0,0,0),world);
        assertEquals(1,materials.remainingItems());
    }
    @Test void flowingOnlySelectionCannotBeReportedAsAlreadyBuilt() {
        var doc=new DirectSchematic("flow only",Path.of("flow.litematic"),Map.of(BlockPos.ZERO,Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL,2)));
        assertTrue(doc.at(BlockPos.ZERO).plan().blocks().isEmpty());
        assertFalse(doc.at(BlockPos.ZERO).recipes().get(BlockPos.ZERO).manualReason().isBlank());
    }
    private static SchematicPreviewJUnitTest.World basin() {
        var world=new SchematicPreviewJUnitTest.World();
        for(var p:WaterPool.cells(BlockPos.ZERO))world.states.put(p.below(),Blocks.STONE.defaultBlockState());
        for(int i=0;i<2;i++) {
            world.states.put(new BlockPos(i,0,-1),Blocks.STONE.defaultBlockState());world.states.put(new BlockPos(i,0,2),Blocks.STONE.defaultBlockState());
            world.states.put(new BlockPos(-1,0,i),Blocks.STONE.defaultBlockState());world.states.put(new BlockPos(2,0,i),Blocks.STONE.defaultBlockState());
        }
        return world;
    }
    @Test void emptyWaterPoolRequiresFourFloorsAndAllEightWalls() {
        var world=basin();assertTrue(WaterPool.safe(world,BlockPos.ZERO,p->true));
        world.states.remove(new BlockPos(-1,0,0));assertFalse(WaterPool.safe(world,BlockPos.ZERO,p->true));
        world=basin();world.states.remove(BlockPos.ZERO.below());assertFalse(WaterPool.safe(world,BlockPos.ZERO,p->true));
        assertFalse(WaterPool.safe(basin(),BlockPos.ZERO,p->!p.equals(BlockPos.ZERO)));
    }
    @Test void transparentGlassStillProvidesAValidPoolFloorAndWalls() {
        var world=basin();world.states.replaceAll((p,state)->Blocks.GLASS.defaultBlockState());
        assertFalse(Blocks.GLASS.defaultBlockState().isSolidRender());
        assertTrue(WaterPool.safe(world,BlockPos.ZERO,p->true));
    }
    @Test void twoDiagonalSeedsAreNotAcceptedAsAnAlreadyRegeneratingPool() {
        var world=basin();world.states.put(BlockPos.ZERO,Blocks.WATER.defaultBlockState());world.states.put(new BlockPos(1,0,1),Blocks.WATER.defaultBlockState());
        assertFalse(WaterPool.ready(world,BlockPos.ZERO));
        for(var p:WaterPool.cells(BlockPos.ZERO))world.states.put(p,Blocks.WATER.defaultBlockState());
        assertTrue(WaterPool.ready(world,BlockPos.ZERO));
    }
}
