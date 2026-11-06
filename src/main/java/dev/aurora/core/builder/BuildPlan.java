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
    public record Block(int x, int y, int z, String item, int count, String state) {
        public Block {
            if (item == null || item.isBlank() || state == null || state.isBlank() || count < 1 || count > 64)
                throw new IllegalArgumentException("Unsupported schematic material");
        }
    }
    private final List<Block> blocks;

    public BuildPlan(List<Block> blocks) {
        if (blocks.size() > 262144) throw new IllegalArgumentException("Schematic exceeds the 262144-block planning limit");
        Set<String> positions = new HashSet<>();
        for (Block b : blocks) if (!positions.add(b.x + "," + b.y + "," + b.z)) throw new IllegalArgumentException("Overlapping schematic blocks");
        this.blocks = blocks.stream().sorted(Comparator.comparingInt(Block::y).thenComparingInt(Block::z).thenComparingInt(Block::x)).toList();
    }
    public List<Block> blocks() { return blocks; }
    public List<Block> remaining(Predicate<Block> placed) { return blocks.stream().filter(b -> !placed.test(b)).toList(); }
    public static Map<String, Integer> materials(List<Block> blocks) {
        Map<String, Integer> result = new TreeMap<>();
        for (Block block : blocks) result.merge(block.item, block.count, Math::addExact);
        return Map.copyOf(result);
    }
    public static Map<String, Integer> missing(Map<String, Integer> required, Map<String, Integer> available) {
        Map<String, Integer> result = new TreeMap<>();
        required.forEach((item, amount) -> { int shortfall = amount - Math.max(0, available.getOrDefault(item, 0)); if (shortfall > 0) result.put(item, shortfall); });
        return Map.copyOf(result);
    }
}
