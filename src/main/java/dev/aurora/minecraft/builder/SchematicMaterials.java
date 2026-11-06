package dev.aurora.minecraft.builder;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

/** A checklist of the selected file, independent of whether automatic placement supports every block. */
public final class SchematicMaterials {
    public record Row(String item, String name, int total, int remaining, boolean manual) { }
    private record Entry(BlockPos pos,BlockState state,String key,int count,List<BlockPos> companions) { }
    private final List<Entry> blocks=new ArrayList<>();
    private final Map<BlockPos,Integer> indices=new HashMap<>();
    private final Map<String,Row> totals=new TreeMap<>();
    private final Map<String,Integer> remaining=new HashMap<>();
    private final int[] counts;
    private int cursor;
    public SchematicMaterials(DirectSchematic document,BlockPos anchor) {
        Map<BlockPos,BlockState> world=new HashMap<>();
        document.blocks().forEach((pos,state)->world.put(anchor.offset(pos),state));
        expectedStates.putAll(world);
        Map<BlockPos,List<BlockPos>> companions=new HashMap<>();Set<BlockPos> generated=new HashSet<>();
        for(var e:world.entrySet()) {
            BlockPos parent=parent(e.getKey(),e.getValue());
            if(parent!=null && world.containsKey(parent) && world.get(parent).getBlock()==e.getValue().getBlock()) {
                generated.add(e.getKey());companions.computeIfAbsent(parent,p->new ArrayList<>()).add(e.getKey());
            }
        }
        for(var e:world.entrySet()) {
            if(generated.contains(e.getKey()))continue;
            var state=e.getValue();
            if(SchematicFluids.liquid(state) && !SchematicFluids.source(state))continue;
            var item=SchematicFluids.source(state)?(state.is(Blocks.WATER)?Items.WATER_BUCKET:Items.LAVA_BUCKET):state.getBlock().asItem();
            boolean manual=item==Items.AIR;
            String id=manual?"":BuiltInRegistries.ITEM.getKey(item).toString();
            String key=manual?"block:"+BuiltInRegistries.BLOCK.getKey(state.getBlock()):id;
            String name=state.getBlock().getName().getString();
            int count=state.getBlock() instanceof SlabBlock && state.getValue(SlabBlock.TYPE)==SlabType.DOUBLE?2:1;
            var previous=totals.get(key);totals.put(key,new Row(id,name,count+(previous==null?0:previous.total()),0,manual));
            remaining.merge(key,count,Integer::sum);
            int index=blocks.size();indices.put(e.getKey(),index);
            List<BlockPos> extra=List.copyOf(companions.getOrDefault(e.getKey(),List.of()));
            for(var pos:extra)indices.put(pos,index);
            blocks.add(new Entry(e.getKey(),state,key,count,extra));
        }
        counts=blocks.stream().mapToInt(Entry::count).toArray();
    }
    /** Compatibility for the immutable integration-test/build snapshot. */
    public SchematicMaterials(DirectSchematic.Snapshot snapshot) {
        this(new DirectSchematic("Checklist",java.nio.file.Path.of("checklist.litematic"),snapshot.states()),BlockPos.ZERO);
    }
    private static BlockPos parent(BlockPos pos,BlockState state) {
        if((state.getBlock() instanceof DoorBlock || state.getBlock() instanceof DoublePlantBlock) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==DoubleBlockHalf.UPPER)return pos.below();
        if(state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART)==BedPart.HEAD)return pos.relative(state.getValue(BedBlock.FACING).getOpposite());
        return null;
    }
    public List<Row> rows() {return totals.entrySet().stream().map(e->{var r=e.getValue();return new Row(r.item(),r.name(),cap(r.item(),r.total()),cap(r.item(),remaining.getOrDefault(e.getKey(),0)),r.manual());}).toList();}
    public int remainingItems() {return rows().stream().mapToInt(Row::remaining).sum();}
    public int totalItems() {return rows().stream().mapToInt(Row::total).sum();}
    private static int cap(String item,int count) {return item.equals("minecraft:water_bucket")?Math.min(2,count):count;}
    public boolean hasManualBlocks() {return totals.values().stream().anyMatch(Row::manual);}
    public void changed(BlockPos pos,BlockGetter world) {var index=indices.get(pos);if(index!=null)refresh(index,world);}
    public void tick(BlockGetter world) {
        int budget=Math.min(16384,blocks.size());
        for(int i=0;i<budget;i++) {refresh(cursor,world);cursor=(cursor+1)%blocks.size();}
    }
    private void refresh(int index,BlockGetter world) {
        var b=blocks.get(index);var expected=b.state();var actual=world.getBlockState(b.pos());
        boolean correct=actual==expected && b.companions().stream().allMatch(p->world.getBlockState(p)==expectedStates.get(p));
        int needed=correct?0:b.count();
        if(!correct && expected.getBlock() instanceof SlabBlock && expected.getValue(SlabBlock.TYPE)==SlabType.DOUBLE
            && actual.getBlock()==expected.getBlock() && actual.getValue(SlabBlock.TYPE)!=SlabType.DOUBLE)needed=1;
        if(needed!=counts[index]) {remaining.merge(b.key(),needed-counts[index],Integer::sum);counts[index]=needed;}
    }
    private final Map<BlockPos,BlockState> expectedStates=new HashMap<>();
}
