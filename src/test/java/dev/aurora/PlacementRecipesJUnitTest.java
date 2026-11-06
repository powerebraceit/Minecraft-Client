package dev.aurora;

import dev.aurora.core.builder.BuildPlan;
import dev.aurora.minecraft.builder.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class PlacementRecipesJUnitTest {
    @BeforeAll static void bootstrap() {SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    private static DirectSchematic doc(BlockState state) {return new DirectSchematic("recipe",Path.of("recipe.litematic"),Map.of(BlockPos.ZERO,state));}
    @Test void everyRegisteredPottedPlantUsesItsActualPlantAndOnePot() {
        int plants=0;
        for(var block:BuiltInRegistries.BLOCK)if(block instanceof FlowerPotBlock pot && pot.getPotted()!=Blocks.AIR) {
            var recipe=PlacementRecipes.describe(block.defaultBlockState());
            assertEquals(List.of(Items.FLOWER_POT,pot.getPotted().asItem()),recipe.steps().stream().map(PlacementRecipes.Step::item).toList(),block.toString());
            assertEquals(PlacementRecipes.Kind.INTERACT,recipe.steps().getLast().kind());plants++;
        }
        assertTrue(plants>25);
    }
    @Test void redMushroomPotHandlesPartialProgressAndMining() {
        var world=new SchematicPreviewJUnitTest.World();var materials=new SchematicMaterials(doc(Blocks.POTTED_RED_MUSHROOM.defaultBlockState()),BlockPos.ZERO);
        assertEquals(Map.of("minecraft:flower_pot",1,"minecraft:red_mushroom",1),BuildPlan.materials(doc(Blocks.POTTED_RED_MUSHROOM.defaultBlockState()).at(BlockPos.ZERO).plan().blocks()));
        materials.tick(world);assertEquals(2,materials.remainingItems());
        world.states.put(BlockPos.ZERO,Blocks.FLOWER_POT.defaultBlockState());materials.changed(BlockPos.ZERO,world);
        assertEquals(1,materials.remainingItems());assertEquals(0,materials.rows().stream().filter(r->r.item().equals("minecraft:flower_pot")).findFirst().orElseThrow().remaining());
        world.states.put(BlockPos.ZERO,Blocks.POTTED_RED_MUSHROOM.defaultBlockState());materials.changed(BlockPos.ZERO,world);assertEquals(0,materials.remainingItems());
        world.states.clear();materials.changed(BlockPos.ZERO,world);assertEquals(2,materials.remainingItems());
    }
    @Test void everyCandleCakeUsesCakeAndMatchingCandleAndCanBeLit() {
        int cakes=0;
        for(var block:BuiltInRegistries.BLOCK)if(block instanceof CandleCakeBlock) {
            var recipe=PlacementRecipes.describe(block.defaultBlockState().setValue(CandleCakeBlock.LIT,true));
            assertEquals(3,recipe.steps().size(),block.toString());
            assertSame(Items.CAKE,recipe.steps().getFirst().item());assertInstanceOf(CandleBlock.class,((BlockItem)recipe.steps().get(1).item()).getBlock());
            assertSame(Items.FIRE_CHARGE,recipe.steps().getLast().item());cakes++;
        }
        assertEquals(17,cakes);
    }
    @Test void quantitiesAndWaterloggingAreActualOrderedActions() {
        for(var state:List.of(Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS,7),Blocks.DYED_CANDLE.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState().setValue(CandleBlock.CANDLES,4),Blocks.TURTLE_EGG.defaultBlockState().setValue(TurtleEggBlock.EGGS,4))) {
            var recipe=PlacementRecipes.describe(state);assertEquals(state.is(Blocks.SNOW)?7:4,recipe.steps().size());
            for(int i=0;i<recipe.steps().size();i++)assertEquals(i,recipe.progress(recipe.steps().get(i).state()));
        }
        var wet=Blocks.IRON_CHAIN.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,true);var snapshot=doc(wet).at(BlockPos.ZERO);
        assertEquals(Map.of("minecraft:iron_chain",1,"minecraft:water_bucket",1),BuildPlan.materials(snapshot.plan().blocks()));
        assertEquals(List.of(0,1),snapshot.plan().blocks().stream().map(BuildPlan.Block::phase).toList());
        assertFalse(snapshot.recipes().get(BlockPos.ZERO).done(1,wet.setValue(BlockStateProperties.WATERLOGGED,false)));
    }
    @Test void wallVariantsSeedsAndRedstoneUseTheRegisteredInventoryItem() {
        for(var block:List.of(Blocks.WALL_TORCH,Blocks.OAK_WALL_SIGN,Blocks.SKELETON_WALL_SKULL,Blocks.WHEAT,Blocks.REDSTONE_WIRE,Blocks.SWEET_BERRY_BUSH,Blocks.TRIPWIRE)) {
            var recipe=PlacementRecipes.describe(block.defaultBlockState());
            assertFalse(recipe.steps().isEmpty(),block.toString());assertNotSame(Items.AIR,recipe.steps().getFirst().item());
        }
    }
    @Test void unsupportedGeneratedBlocksRemainVisibleWithoutBlockingOtherMaterials() {
        var selected=new DirectSchematic("manual",Path.of("manual.litematic"),Map.of(BlockPos.ZERO,Blocks.MOVING_PISTON.defaultBlockState(),BlockPos.ZERO.above(),Blocks.POTTED_RED_MUSHROOM.defaultBlockState()));
        var snapshot=selected.at(BlockPos.ZERO);assertEquals(2,snapshot.plan().blocks().size());
        var materials=new SchematicMaterials(snapshot);assertTrue(materials.hasManualBlocks());assertEquals(3,materials.rows().size());
    }
    @Test void orphanUpperHalfRemainsManualWithoutHidingOtherItems() {
        var upper=Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER);
        var selected=new DirectSchematic("partial",Path.of("partial.litematic"),Map.of(BlockPos.ZERO,upper,BlockPos.ZERO.above(),Blocks.STONE.defaultBlockState()));
        var snapshot=selected.at(BlockPos.ZERO);assertEquals(1,snapshot.plan().blocks().size());
        assertFalse(snapshot.recipes().get(BlockPos.ZERO).manualReason().isBlank());
        assertEquals(2,new SchematicMaterials(snapshot).rows().size());
    }
    @Test void auditEveryRegisteredBlockAndEveryStateWithoutThrowing() throws Exception {
        var report=new StringBuilder("Block\tActions\tMaterials\tQualification\n");int blocks=0,states=0,manual=0;
        for(var block:BuiltInRegistries.BLOCK) {
            for(var state:block.getStateDefinition().getPossibleStates()) {assertNotNull(PlacementRecipes.describe(state),state.toString());states++;}
            var recipe=PlacementRecipes.describe(block.defaultBlockState());
            var costs=new TreeMap<String,Integer>();for(var step:recipe.steps()) {assertNotSame(Items.AIR,step.item(),block.toString());costs.merge(step.itemId(),1,Integer::sum);}
            if(recipe.steps().isEmpty() && !block.defaultBlockState().isAir())manual++;
            report.append(BuiltInRegistries.BLOCK.getKey(block)).append('\t').append(recipe.steps().size()).append('\t').append(costs).append('\t').append(recipe.manualReason().isBlank()?"Native placement, world support and final-state validation required":recipe.manualReason()).append('\n');blocks++;
        }
        assertTrue(blocks>1000);assertTrue(states>10000);
        Path folder=Path.of("build/reports/placement");Files.createDirectories(folder);Files.writeString(folder.resolve("registry-coverage.tsv"),report);
        Files.writeString(folder.resolve("summary.txt"),"Registered blocks: "+blocks+"\nStates audited: "+states+"\nDefault states without direct automatic actions: "+manual+"\n");
    }
}
