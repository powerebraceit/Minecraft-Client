package dev.aurora.core.builder;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Immutable layer order and material requirements for a transformed schematic snapshot. */
public final class BuildPlan {
    public record Block(int x, int y, int z, String item, int count, String state, int stage, int phase) {
        public Block(int x,int y,int z,String item,int count,String state) {this(x,y,z,item,count,state,0,item.equals("minecraft:water_bucket") || item.equals("minecraft:lava_bucket")?1:0);}
        public Block {
            if (item == null || item.isBlank() || state == null || state.isBlank() || count < 1 || count > 64 || stage<0 || phase<0)
                throw new IllegalArgumentException("Unsupported schematic material");
        }
    }
    private final List<Block> blocks;

    public BuildPlan(List<Block> blocks) {
        if (blocks.size() > 262144) throw new IllegalArgumentException("Schematic exceeds the 262144-action planning limit");
        Set<String> positions = new HashSet<>();
        for (Block b : blocks) if (!positions.add(b.x + "," + b.y + "," + b.z + ":" + b.stage)) throw new IllegalArgumentException("Overlapping schematic blocks");
        this.blocks = blocks.stream().sorted(Comparator.comparingInt(Block::phase).thenComparingInt(Block::y).thenComparingInt(Block::z).thenComparingInt(Block::x).thenComparingInt(Block::stage)).toList();
    }
    public List<Block> blocks() { return blocks; }
    public List<Block> remaining(Predicate<Block> placed) { return blocks.stream().filter(b -> !placed.test(b)).toList(); }
    public static Map<String, Integer> materials(List<Block> blocks) {
        Map<String, Integer> result = new TreeMap<>();
        for (Block block : blocks) result.merge(block.item, block.count, Math::addExact);
        result.computeIfPresent("minecraft:water_bucket",(item,count)->Math.min(2,count));
        return Map.copyOf(result);
    }
    public static boolean fluid(Block block) {return block.item.equals("minecraft:water_bucket") || block.item.equals("minecraft:lava_bucket");}
    public static Map<String, Integer> missing(Map<String, Integer> required, Map<String, Integer> available) {
        Map<String, Integer> result = new TreeMap<>();
        required.forEach((item, amount) -> { int shortfall = amount - Math.max(0, available.getOrDefault(item, 0)); if (shortfall > 0) result.put(item, shortfall); });
        return Map.copyOf(result);
    }
}
