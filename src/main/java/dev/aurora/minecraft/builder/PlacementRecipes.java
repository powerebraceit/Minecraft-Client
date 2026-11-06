package dev.aurora.minecraft.builder;

import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

/** Inventory recipes describe actions, rather than assuming every schematic block has its own item. */
public final class PlacementRecipes {
    public enum Kind { PLACE, INTERACT, BUCKET, AIR_USE }
    public record Step(Item item, BlockState state, Kind kind, int phase, List<BlockState> alternatives) {
        public Step(Item item,BlockState state,Kind kind,int phase) {this(item,state,kind,phase,List.of());}
        public Step {alternatives=List.copyOf(alternatives);}
        public String itemId() {return BuiltInRegistries.ITEM.getKey(item).toString();}
        public boolean matches(BlockState actual) {return equivalent(actual,state) || alternatives.stream().anyMatch(s->equivalent(actual,s));}
    }
    public record Recipe(BlockState desired,List<Step> steps,String manualReason) {
        public Recipe {steps=List.copyOf(steps);}
        public int progress(BlockState actual) {
            if(actual==desired)return steps.size()-1;
            for(int i=steps.size()-1;i>=0;i--)if(steps.get(i).matches(actual))return i;
            return -1;
        }
        public boolean done(int stage,BlockState actual) {return progress(actual)>=stage;}
        public boolean canContinue(int stage,BlockState actual) {return stage>0 && progress(actual)==stage-1;}
    }
    private PlacementRecipes() { }
    public static Recipe describe(BlockState desired) {
        List<Step> steps=new ArrayList<>();Block block=desired.getBlock();
        if(desired.isAir())return new Recipe(desired,steps,"");
        if(SchematicFluids.liquid(desired)) {
            if(SchematicFluids.source(desired))steps.add(new Step(desired.is(Blocks.WATER)?Items.WATER_BUCKET:Items.LAVA_BUCKET,desired,Kind.BUCKET,1));
            return new Recipe(desired,steps,SchematicFluids.source(desired)?"":"Flowing fluid requires its source and matching terrain");
        }
        boolean wet=desired.hasProperty(BlockStateProperties.WATERLOGGED) && desired.getValue(BlockStateProperties.WATERLOGGED);
        BlockState dry=wet?desired.setValue(BlockStateProperties.WATERLOGGED,false):desired;
        boolean lit=(block instanceof CandleBlock || block instanceof CandleCakeBlock) && dry.getValue(BlockStateProperties.LIT);
        if(lit)dry=dry.setValue(BlockStateProperties.LIT,false);
        if(block instanceof FlowerPotBlock pot && pot.getPotted()!=Blocks.AIR) {
            steps.add(new Step(Items.FLOWER_POT,Blocks.FLOWER_POT.defaultBlockState(),Kind.PLACE,0));
            var content=pot.getPotted().asItem();
            if(content==Items.AIR)return new Recipe(desired,steps,"Potted plant has no registered inventory item");
            steps.add(new Step(content,dry,Kind.INTERACT,0));
        } else if(block instanceof CandleCakeBlock) {
            String id=BuiltInRegistries.BLOCK.getKey(block).getPath();
            if(!id.endsWith("_cake"))return new Recipe(desired,steps,"Candle cake needs a registered matching candle recipe");
            var candle=BuiltInRegistries.ITEM.getOptional(Identifier.fromNamespaceAndPath(BuiltInRegistries.BLOCK.getKey(block).getNamespace(),id.substring(0,id.length()-5))).orElse(Items.AIR);
            if(!(candle instanceof BlockItem))return new Recipe(desired,steps,"Candle cake has no registered matching candle");
            steps.add(new Step(Items.CAKE,Blocks.CAKE.defaultBlockState(),Kind.PLACE,0));
            steps.add(new Step(candle,dry,Kind.INTERACT,0));
        } else if(block==Blocks.WATER_CAULDRON || block==Blocks.LAVA_CAULDRON || block==Blocks.POWDER_SNOW_CAULDRON) {
            if(block instanceof LayeredCauldronBlock && desired.getValue(LayeredCauldronBlock.LEVEL)!=3)
                return new Recipe(desired,List.of(),"Partial cauldron levels require manual filling/draining");
            steps.add(new Step(Items.CAULDRON,Blocks.CAULDRON.defaultBlockState(),Kind.PLACE,0));
            steps.add(new Step(block==Blocks.LAVA_CAULDRON?Items.LAVA_BUCKET:block==Blocks.WATER_CAULDRON?Items.WATER_BUCKET:Items.POWDER_SNOW_BUCKET,desired,Kind.INTERACT,1));
        } else if(block==Blocks.POWDER_SNOW) {
            steps.add(new Step(Items.POWDER_SNOW_BUCKET,desired,Kind.PLACE,1));
        } else {
            Item item=block.asItem();
            if(!(item instanceof BlockItem))return new Recipe(desired,steps,"No directly placeable survival item; requires manual creation or growth");
            Kind kind=item instanceof PlaceOnWaterBlockItem?Kind.AIR_USE:Kind.PLACE;
            if(block instanceof SlabBlock && dry.getValue(SlabBlock.TYPE)==SlabType.DOUBLE) {
                var bottom=dry.setValue(SlabBlock.TYPE,SlabType.BOTTOM);var top=dry.setValue(SlabBlock.TYPE,SlabType.TOP);
                steps.add(new Step(item,bottom,kind,0,List.of(top)));steps.add(new Step(item,dry,kind,0));
            }else {
                IntegerProperty amount=amount(dry);int count=amount==null?1:dry.getValue(amount);
                for(int i=1;i<=count;i++)steps.add(new Step(item,amount==null?dry:dry.setValue(amount,i),kind,kind==Kind.AIR_USE?2:0));
            }
        }
        if(lit && !wet)steps.add(new Step(Items.FIRE_CHARGE,dry.setValue(BlockStateProperties.LIT,true),Kind.INTERACT,0));
        if(wet)steps.add(new Step(Items.WATER_BUCKET,desired,Kind.BUCKET,1));
        return new Recipe(desired,steps,lit && wet?"Waterlogged candles cannot stay lit":"");
    }
    private static IntegerProperty amount(BlockState state) {
        Block block=state.getBlock();
        if(block instanceof SnowLayerBlock)return SnowLayerBlock.LAYERS;
        if(block instanceof CandleBlock)return CandleBlock.CANDLES;
        if(block instanceof SeaPickleBlock)return SeaPickleBlock.PICKLES;
        if(block instanceof TurtleEggBlock)return TurtleEggBlock.EGGS;
        if(block instanceof SegmentableBlock segment)return segment.getSegmentAmountProperty();
        return null;
    }
    /** These properties are recomputed by neighbors, redstone or growth after placement. Final validation stays exact. */
    private static final Set<String> DERIVED=Set.of("age","stage","distance","persistent","powered","power","north","south","east","west","up","down","shape","type","instrument","note","occupied");
    public static boolean equivalent(BlockState actual,BlockState expected) {
        if(actual==expected)return true;
        if(actual==null || actual.getBlock()!=expected.getBlock())return false;
        for(var property:expected.getProperties()) {
            String name=property.getName();
            if(DERIVED.contains(name) && !(expected.getBlock() instanceof SlabBlock && name.equals("type")))continue;
            if(!actual.getValue(property).equals(expected.getValue(property)))return false;
        }
        return true;
    }
}
