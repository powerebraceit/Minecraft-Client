package dev.aurora.minecraft.builder;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Materials and placement share the same recipes, including intermediate items and partial progress. */
public final class SchematicMaterials {
    public record Row(String item,String name,int total,int remaining,boolean manual) { }
    private record Entry(BlockPos pos,PlacementRecipes.Recipe recipe,int stage,String key,List<BlockPos> companions) { }
    private final DirectSchematic.Snapshot snapshot;
    private final List<Entry> entries=new ArrayList<>();
    private final Map<BlockPos,List<Integer>> indices=new HashMap<>();
    private final Map<String,Row> totals=new TreeMap<>();
    private final Map<String,Integer> remaining=new HashMap<>();
    private final int[] counts;
    private int cursor;
    public SchematicMaterials(DirectSchematic document,BlockPos anchor) {this(document.at(anchor));}
    public SchematicMaterials(DirectSchematic.Snapshot snapshot) {
        this.snapshot=snapshot;
        for(var e:snapshot.recipes().entrySet()) {
            var pos=e.getKey();var recipe=e.getValue();
            for(int stage=0;stage<recipe.steps().size();stage++) {
                var step=recipe.steps().get(stage);String key=step.itemId();
                add(new Entry(pos,recipe,stage,key,snapshot.companions().getOrDefault(pos,List.of())),key,net.minecraft.network.chat.Component.translatable(step.item().getDescriptionId()).getString(),false);
            }
            if(recipe.steps().isEmpty() && !recipe.manualReason().isBlank() && !SchematicFluids.liquid(recipe.desired())) {
                String key="block:"+BuiltInRegistries.BLOCK.getKey(recipe.desired().getBlock());
                add(new Entry(pos,recipe,-1,key,List.of()),"",recipe.desired().getBlock().getName().getString(),true);
            }
        }
        counts=new int[entries.size()];Arrays.fill(counts,1);
    }
    private void add(Entry entry,String item,String name,boolean manual) {
        var previous=totals.get(entry.key());
        totals.put(entry.key(),new Row(item,name,previous==null?1:previous.total()+1,0,manual));
        remaining.merge(entry.key(),1,Integer::sum);
        int index=entries.size();indices.computeIfAbsent(entry.pos(),p->new ArrayList<>()).add(index);
        for(var extra:entry.companions())indices.computeIfAbsent(extra,p->new ArrayList<>()).add(index);
        entries.add(entry);
    }
    public List<Row> rows() {return totals.entrySet().stream().map(e->{var r=e.getValue();return new Row(r.item(),r.name(),cap(r.item(),r.total()),cap(r.item(),remaining.getOrDefault(e.getKey(),0)),r.manual());}).toList();}
    public int remainingItems() {return rows().stream().mapToInt(Row::remaining).sum();}
    public int totalItems() {return rows().stream().mapToInt(Row::total).sum();}
    private static int cap(String item,int count) {return item.equals("minecraft:water_bucket")?Math.min(2,count):count;}
    public boolean hasManualBlocks() {return snapshot.recipes().values().stream().anyMatch(r->!r.manualReason().isBlank());}
    public void changed(BlockPos pos,BlockGetter world) {for(int i:indices.getOrDefault(pos,List.of()))refresh(i,world);}
    public void tick(BlockGetter world) {
        int budget=Math.min(16384,entries.size());
        for(int i=0;i<budget;i++) {refresh(cursor,world);cursor=(cursor+1)%entries.size();}
    }
    private void refresh(int index,BlockGetter world) {
        var entry=entries.get(index);BlockState actual=world.getBlockState(entry.pos());
        boolean done=entry.stage()<0?actual==entry.recipe().desired():entry.recipe().done(entry.stage(),actual);
        if(done)done=entry.companions().stream().allMatch(p->PlacementRecipes.equivalent(world.getBlockState(p),snapshot.states().get(p)));
        int needed=done?0:1;
        if(needed!=counts[index]) {remaining.merge(entry.key(),needed-counts[index],Integer::sum);counts[index]=needed;}
    }
}
