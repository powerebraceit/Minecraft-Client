package dev.aurora;

import dev.aurora.minecraft.builder.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class SchematicPreviewJUnitTest {
    @BeforeAll static void bootstrap() {SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    static final class World implements BlockGetter {
        final Map<BlockPos,BlockState> states=new HashMap<>();
        public BlockState getBlockState(BlockPos p) {return states.getOrDefault(p,Blocks.AIR.defaultBlockState());}
        public FluidState getFluidState(BlockPos p) {return getBlockState(p).getFluidState();}
        public BlockEntity getBlockEntity(BlockPos p) {return null;}
        public int getHeight() {return 384;}
        public int getMinY() {return -64;}
    }
    private static DirectSchematic document(Map<BlockPos,BlockState> states) {return new DirectSchematic("test",Path.of("test.litematic"),states);}
    @Test void distinguishesMissingWrongOrientationAndCorrectBlocks() {
        var expected=Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.EAST);
        assertEquals(SchematicPreview.Match.MISSING,SchematicPreview.match(expected,Blocks.AIR.defaultBlockState()));
        assertEquals(SchematicPreview.Match.WRONG,SchematicPreview.match(expected,Blocks.DIRT.defaultBlockState()));
        assertEquals(SchematicPreview.Match.WRONG,SchematicPreview.match(expected,expected.setValue(StairBlock.FACING,Direction.NORTH)));
        assertEquals(SchematicPreview.Match.CORRECT,SchematicPreview.match(expected,expected));
        assertEquals(SchematicPreview.Match.WRONG,SchematicPreview.match(expected,Blocks.SHORT_GRASS.defaultBlockState()));
    }
    @Test void placementAndMiningUpdateRequiredItems() {
        var pos=BlockPos.ZERO;var state=Blocks.STONE.defaultBlockState();var world=new World();
        var materials=new SchematicMaterials(document(Map.of(pos,state,pos.above(),state)).at(BlockPos.ZERO));
        materials.tick(world);assertEquals(2,materials.remainingItems());
        world.states.put(pos,state);materials.changed(pos,world);assertEquals(1,materials.remainingItems());
        world.states.remove(pos);materials.changed(pos,world);assertEquals(2,materials.remainingItems());
        world.states.put(pos,Blocks.DIRT.defaultBlockState());materials.tick(world);assertEquals(2,materials.remainingItems());
        assertEquals(2,materials.rows().getFirst().total());
    }
    @Test void doubleSlabCountsBothItemsAndRecognizesExistingSingleSlab() {
        var pos=BlockPos.ZERO;var single=Blocks.STONE_SLAB.defaultBlockState();var doubled=single.setValue(SlabBlock.TYPE,SlabType.DOUBLE);var world=new World();
        var materials=new SchematicMaterials(document(Map.of(pos,doubled)).at(BlockPos.ZERO));
        materials.tick(world);assertEquals(2,materials.remainingItems());
        world.states.put(pos,single);materials.tick(world);assertEquals(1,materials.remainingItems());
        world.states.put(pos,doubled);materials.changed(pos,world);assertEquals(0,materials.remainingItems());
        world.states.remove(pos);materials.changed(pos,world);assertEquals(2,materials.remainingItems());
    }
    @Test void automaticUpperHalfDoesNotCountAsAnotherItemAndMiningItRestoresRequirement() {
        var pos=BlockPos.ZERO;var lower=Blocks.OAK_DOOR.defaultBlockState();var upper=lower.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER);var world=new World();
        var materials=new SchematicMaterials(document(Map.of(pos,lower,pos.above(),upper)).at(BlockPos.ZERO));
        assertEquals(1,materials.remainingItems());
        world.states.put(pos,lower);materials.tick(world);assertEquals(1,materials.remainingItems());
        world.states.put(pos.above(),upper);materials.changed(pos.above(),world);assertEquals(0,materials.remainingItems());
        world.states.remove(pos.above());materials.changed(pos.above(),world);assertEquals(1,materials.remainingItems());
    }
    @Test void unsupportedAutomaticPlacementDoesNotHideTheSelectedFilesMaterials() {
        var pos=BlockPos.ZERO;
        var selected=document(Map.of(pos,Blocks.STONE.defaultBlockState(),pos.above(),Blocks.MOVING_PISTON.defaultBlockState()));
        assertEquals(1,selected.at(BlockPos.ZERO).plan().blocks().size());
        var materials=new SchematicMaterials(selected,BlockPos.ZERO);materials.tick(new World());
        assertEquals(2,materials.rows().size());
        assertEquals(1,materials.rows().stream().filter(r->r.item().equals("minecraft:stone")).findFirst().orElseThrow().total());
        assertTrue(materials.rows().stream().anyMatch(r->r.manual() && r.total()==1));
        assertTrue(materials.hasManualBlocks());
    }
    @Test void selectingAnotherDocumentReplacesMaterialTypesAndCounts() {
        var first=new SchematicMaterials(document(Map.of(BlockPos.ZERO,Blocks.STONE.defaultBlockState())),BlockPos.ZERO);
        var second=new SchematicMaterials(document(Map.of(BlockPos.ZERO,Blocks.OAK_PLANKS.defaultBlockState(),BlockPos.ZERO.above(),Blocks.OAK_PLANKS.defaultBlockState())),BlockPos.ZERO);
        assertEquals(1,first.totalItems());assertEquals(2,second.totalItems());
        assertEquals("minecraft:oak_planks",second.rows().getFirst().item());assertEquals(1,second.rows().size());
    }

}
