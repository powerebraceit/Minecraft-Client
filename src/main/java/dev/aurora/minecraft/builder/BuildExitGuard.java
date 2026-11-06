package dev.aurora.minecraft.builder;

import dev.aurora.core.builder.AStarPathfinder;
import dev.aurora.core.builder.AStarPathfinder.*;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Checks a solid placement against a remembered, reachable exit before clicking.
 *
 * <p>The prospective block exists only in a read-only overlay. A path found before placement is
 * insufficient: a block in the last doorway can invalidate that path. This guard therefore asks
 * the same safe-terrain A* engine to prove a route in the prospective world. No partial search or
 * node-budget exhaustion is accepted as proof of an escape route.
 */
public final class BuildExitGuard {
    private static final int SEARCH_RADIUS = 48;
    private static final int MAX_EXPANDED = 4096;

    private record Check(Cell start, Cell exit, BlockPos target, BlockState expected) { }
    private final Map<Check, Boolean> checked = new HashMap<>();
    private List<BlockPos> preferredExits = List.of();
    private Object configuredWorld;
    private Cell exit;
    private int minX, maxX, minZ, maxZ;
    private String reason = "Choose a schematic before checking escape routes";

    public String reason() { return reason; }
    public Optional<BlockPos> exit() {
        return exit == null ? Optional.empty() : Optional.of(pos(exit));
    }
    public void configure(DirectSchematic.Snapshot schematic) { configure(schematic, List.of()); }

    /** Preferred restock-area feet positions must also be outside the schematic selection. */
    public void configure(DirectSchematic.Snapshot schematic, Collection<BlockPos> preferred) {
        var mc = Minecraft.getInstance();
        configuredWorld = mc.level;
        preferredExits = preferred.stream().map(BlockPos::immutable).toList();
        checked.clear();
        exit = null;
        minX = schematic.states().keySet().stream().mapToInt(BlockPos::getX).min().orElse(0);
        maxX = schematic.states().keySet().stream().mapToInt(BlockPos::getX).max().orElse(0);
        minZ = schematic.states().keySet().stream().mapToInt(BlockPos::getZ).min().orElse(0);
        maxZ = schematic.states().keySet().stream().mapToInt(BlockPos::getZ).max().orElse(0);
        if (mc.player == null || mc.level == null) {
            reason = "Join a world before checking the build exit";
            return;
        }
        chooseExit(cell(mc.player.blockPosition()));
    }

    /** Server block updates invalidate every simulated proof; the next action reads live terrain. */
    public void confirmed(BlockPos changed) { checked.clear(); }

    /**
     * Returns false if a safe exit cannot be verified after this placement.
     * A clear reason can be shown by the placement loop; the queue must not silently skip it.
     */
    public boolean canPlace(BlockPos target, BlockState expected) {
        var player = Minecraft.getInstance().player;
        if (player == null) { reason = "Join a world before checking the build exit"; return false; }
        return canPlaceFrom(target, expected, player.position());
    }

    /** Also used while choosing a stance: close a final doorway from the exterior side. */
    public boolean canPlaceFrom(BlockPos target, BlockState expected, net.minecraft.world.phys.Vec3 candidateFeet) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || configuredWorld != mc.level) {
            reason = "World changed; verify the build exit again";
            return false;
        }
        // An empty collision shape cannot close a walking passage.
        if (expected.getCollisionShape(mc.level, target).isEmpty()) return true;
        Cell start = cell(BlockPos.containing(candidateFeet));
        Predicate<Cell> loaded = this::loaded;
        Terrain live = terrain(mc.level, loaded);
        if (!live.canStand(start)) {
            reason = "Stand on a safe full block so the escape route can be verified";
            return false;
        }
        if (exit == null || !live.canStand(exit)) chooseExit(start);
        if (exit == null) return false;

        Check key = new Check(start, exit, target.immutable(), expected);
        Boolean cached = checked.get(key);
        if (cached != null) {
            reason = cached ? "Exit remains reachable" : "Placement would block the verified exit";
            return cached;
        }
        Result result = evaluate(mc.level, start, exit, target, expected, loaded,
            bounds(start), MAX_EXPANDED);
        boolean reachable = result.reached();
        if (checked.size() >= 128) checked.clear();
        checked.put(key, reachable);
        reason = reachable ? "Exit remains reachable"
            : "Keep an escape passage open; post-placement route is " + result.status();
        return reachable;
    }

    private void chooseExit(Cell start) {
        var mc = Minecraft.getInstance();
        Terrain live = terrain(mc.level, this::loaded);
        if (!live.canStand(start)) {
            reason = "Stand on a safe full block so the build exit can be verified";
            return;
        }
        // A known, reachable restock-area exit is more useful than an arbitrary nearby boundary.
        var preferred = preferredExits.stream().map(BuildExitGuard::cell)
            .filter(this::outside).filter(bounds(start)::contains).toList();
        Result result = AStarPathfinder.search(start, preferred, live, bounds(start), MAX_EXPANDED);
        if (result.reached()) { remember(result); return; }
        if (outside(start)) { exit = start; reason = "Current safe exterior position retained as the exit"; return; }

        Set<Cell> candidates = new LinkedHashSet<>();
        int lowY = Math.max(Minecraft.getInstance().level.getMinY() + 1, start.y() - 3);
        int highY = Math.min(Minecraft.getInstance().level.getMaxY() - 1, start.y() + 3);
        for (int y = lowY; y <= highY; y++) {
            for (int z = Math.max(minZ - 1, start.z() - SEARCH_RADIUS);
                 z <= Math.min(maxZ + 1, start.z() + SEARCH_RADIUS); z++) {
                candidates.add(new Cell(minX - 1, y, z));
                candidates.add(new Cell(maxX + 1, y, z));
            }
            for (int x = Math.max(minX - 1, start.x() - SEARCH_RADIUS);
                 x <= Math.min(maxX + 1, start.x() + SEARCH_RADIUS); x++) {
                candidates.add(new Cell(x, y, minZ - 1));
                candidates.add(new Cell(x, y, maxZ + 1));
            }
        }
        result = AStarPathfinder.search(start, candidates, live, bounds(start), MAX_EXPANDED);
        if (result.reached()) remember(result);
        else reason = "No verified exterior exit within the loaded 48-block search: " + result.status();
    }
    private void remember(Result result) {
        exit = result.reachedGoal().orElseThrow();
        reason = "Reachable exterior exit verified";
    }
    private boolean outside(Cell feet) {
        return feet.x() < minX || feet.x() > maxX || feet.z() < minZ || feet.z() > maxZ;
    }
    private boolean loaded(Cell cell) {
        var mc = Minecraft.getInstance();
        BlockPos pos = pos(cell);
        return mc.level != null && !mc.level.isOutsideBuildHeight(pos)
            && mc.level.hasChunkAt(pos) && mc.level.getWorldBorder().isWithinBounds(pos);
    }
    private Bounds bounds(Cell start) {
        return new Bounds(start.x() - SEARCH_RADIUS, start.y() - 16, start.z() - SEARCH_RADIUS,
            start.x() + SEARCH_RADIUS, start.y() + 16, start.z() + SEARCH_RADIUS);
    }

    /** Public for pure terrain regression tests; this method never accesses the client singleton. */
    public static Result evaluate(BlockGetter before, Cell start, Cell verifiedExit,
                                  BlockPos changed, BlockState expected, Predicate<Cell> loaded,
                                  Bounds bounds, int maxExpanded) {
        BlockGetter prospective = new Overlay(before, changed.immutable(), expected);
        return AStarPathfinder.search(start, List.of(verifiedExit), terrain(prospective, loaded), bounds, maxExpanded);
    }
    private static Terrain terrain(BlockGetter world, Predicate<Cell> loaded) {
        return new Terrain() {
            @Override public boolean canStand(Cell feet) {
                return loaded.test(feet) && loaded.test(feet.offset(0, 1, 0))
                    && loaded.test(feet.offset(0, -1, 0)) && TerrainSafety.canStand(world, feet);
            }
            @Override public boolean isClear(Cell cell) {
                return loaded.test(cell) && TerrainSafety.isClear(world, cell);
            }
        };
    }
    private static Cell cell(BlockPos pos) { return new Cell(pos.getX(), pos.getY(), pos.getZ()); }
    private static BlockPos pos(Cell cell) { return new BlockPos(cell.x(), cell.y(), cell.z()); }

    private record Overlay(BlockGetter delegate, BlockPos changed, BlockState expected) implements BlockGetter {
        @Override public BlockState getBlockState(BlockPos pos) { return changed.equals(pos) ? expected : delegate.getBlockState(pos); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return changed.equals(pos) ? null : delegate.getBlockEntity(pos); }
        @Override public int getHeight() { return delegate.getHeight(); }
        @Override public int getMinY() { return delegate.getMinY(); }
    }
}
