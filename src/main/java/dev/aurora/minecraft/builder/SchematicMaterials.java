package dev.aurora.minecraft.builder;

import dev.aurora.core.builder.BuildPlan;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Tracks remaining items, including server changes, without scanning a large schematic every frame. */
public final class SchematicMaterials {
    public record Row(String item, int total, int remaining) { }
    private final DirectSchematic.Snapshot schematic;
    private final List<BuildPlan.Block> blocks;
    private final Map<BlockPos,Integer> indices=new HashMap<>();
    private final Map<String,Integer> total,remaining=new TreeMap<>();
    private final int[] counts;
    private int cursor;
    public SchematicMaterials(DirectSchematic.Snapshot schematic) {
        this.schematic=schematic;blocks=schematic.plan().blocks();
        total=BuildPlan.materials(blocks);remaining.putAll(total);counts=new int[blocks.size()];
        for(int i=0;i<blocks.size();i++) {
            var b=blocks.get(i);var pos=new BlockPos(b.x(),b.y(),b.z());indices.put(pos,i);counts[i]=b.count();
            for(var companion:schematic.companions().getOrDefault(pos,List.of()))indices.put(companion,i);
        }
    }
    public List<Row> rows() { return total.keySet().stream().sorted().map(item->new Row(item,total.get(item),remaining.getOrDefault(item,0))).toList(); }
    public int remainingItems() {return remaining.values().stream().mapToInt(Integer::intValue).sum();}
    public void changed(BlockPos pos,BlockGetter world) {var index=indices.get(pos);if(index!=null)refresh(index,world);}
    public void tick(BlockGetter world) {
        // Polling also catches newly loaded chunks and local mining predictions. Packet updates are immediate.
        int budget=Math.min(16384,blocks.size());
        for(int i=0;i<budget;i++) {refresh(cursor,world);cursor=(cursor+1)%blocks.size();}
    }
    private void refresh(int index,BlockGetter world) {
        var b=blocks.get(index);var pos=new BlockPos(b.x(),b.y(),b.z());
        var expected=schematic.states().get(pos);var actual=world.getBlockState(pos);
        boolean correct=actual==expected && schematic.companions().getOrDefault(pos,List.of()).stream().allMatch(p->world.getBlockState(p)==schematic.states().get(p));
        int needed=correct?0:b.count();
        if(!correct && expected.getBlock() instanceof SlabBlock && expected.getValue(SlabBlock.TYPE)==SlabType.DOUBLE
            && actual.getBlock()==expected.getBlock() && actual.getValue(SlabBlock.TYPE)!=SlabType.DOUBLE)needed=1;
        if(needed!=counts[index]) {remaining.merge(b.item(),needed-counts[index],Integer::sum);counts[index]=needed;}
    }
}
