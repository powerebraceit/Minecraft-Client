package dev.aurora.smoke;

import dev.aurora.core.builder.BuildSession;
import dev.aurora.minecraft.AuroraClient;
import dev.aurora.minecraft.builder.DirectSchematic;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Real-client regressions for a changing path and face-dependent vanilla placement. */
public final class AdvancedBuilderCheck {
    private static final BlockPos START = new BlockPos(-448, -60, -628);
    private static final BlockPos TARGET = START.offset(8, 0, 0);
    private static final BlockPos ORIENTATION_START = START.offset(0, 0, 14);
    private static final Map<BlockPos, BlockState> ORIENTED = Map.of(
        ORIENTATION_START.offset(2, 0, 0), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST),
        ORIENTATION_START.offset(2, 0, 3), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH),
        ORIENTATION_START.offset(4, 0, 0), Blocks.PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.WEST)
    );
    private static volatile boolean fixtureReady, wallReady;
    private static int ticks, stage, phaseTick;
    private static Vec3 origin, previousFeet;
    private static double travelled, sideways;
    private static boolean wallRequested;

    private AdvancedBuilderCheck() { }

    public static void start(Minecraft mc, AuroraClient client) {
        client.modules().get("auto_builder").setEnabled(false);
        client.builder().stop("Preparing dynamic A* and orientation regression");
        client.builder().chests.save(List.of());
        mc.gui.setScreen(null);
        ticks = stage = phaseTick = 0;
        fixtureReady = wallReady = wallRequested = false;
        var uuid = mc.player.getUUID();
        mc.getSingleplayerServer().execute(() -> {
            var server = mc.getSingleplayerServer();
            var world = server.overworld();
            var player = server.getPlayerList().getPlayer(uuid);
            player.setGameMode(GameType.SURVIVAL);
            player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(4.5);
            // All terrain changes and teleports in this class create fixtures; the builder itself must walk.
            for (int x = -5; x <= 13; x++) for (int z = -5; z <= 5; z++) {
                world.setBlockAndUpdate(START.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y <= 5; y++) world.setBlockAndUpdate(START.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.STONE));
            player.getInventory().setSelectedSlot(0);
            player.inventoryMenu.broadcastChanges();
            player.teleportTo(START.getX() + 0.5, START.getY(), START.getZ() + 0.5);
            fixtureReady = true;
        });
    }

    public static boolean tick(Minecraft mc, AuroraClient client) {
        if (++ticks > 700) throw new AssertionError("Advanced builder regression timed out: stage=" + stage + ", status=" + client.builder().status());
        if (stage == 0) {
            if (!fixtureReady || ticks < 20 || !mc.player.blockPosition().equals(START)
                || !mc.level.getBlockState(TARGET.below()).is(Blocks.STONE)
                || client.builder().inventory().getOrDefault("minecraft:stone", 0) != 1) return false;
            startDocument(mc, client, Map.of(TARGET, Blocks.STONE.defaultBlockState()), "Replan around newly placed blocks");
            origin = previousFeet = mc.player.position();
            travelled = sideways = 0;
            phaseTick = ticks;
            stage = 1;
            return false;
        }
        if (stage == 1) {
            healthy(client, "Dynamic A* route");
            Vec3 feet = mc.player.position();
            double movement = Math.hypot(feet.x - previousFeet.x, feet.z - previousFeet.z);
            require(movement <= 0.32, "Dynamic navigation must use normal walking, not teleport or a speed boost");
            travelled += movement;
            previousFeet = feet;
            sideways = Math.max(sideways, Math.abs(feet.z - origin.z));
            require(travelled <= 18, "Replanning must terminate without circles or a repeated wandering route");
            require(ticks - phaseTick <= 240, "A small new wall must trigger a bounded, prompt replan");
            require(mc.player.isAlive(), "The route must not lead the player into a lethal hazard");
            require(!mc.level.getBlockState(mc.player.blockPosition()).is(Blocks.LAVA)
                && !mc.level.getBlockState(mc.player.blockPosition().below()).is(Blocks.LAVA), "The route must avoid lava feet/support cells");
            require(feet.y >= START.getY() - 0.05 && feet.y <= START.getY() + 2.6, "Navigation must stay on supported terrain or ordinary one-block jumps");
            if (!wallRequested && feet.x - origin.x >= 0.8) {
                wallRequested = true;
                mc.getSingleplayerServer().execute(() -> {
                    var world = mc.getSingleplayerServer().overworld();
                    // This update arrives after path selection, like a block placed by an earlier action.
                    for (int z = -1; z <= 1; z++) for (int y = 0; y <= 1; y++)
                        world.setBlockAndUpdate(START.offset(3, y, z), Blocks.STONE.defaultBlockState());
                    // One detour has a contained lava cell; a valid route remains on the other side.
                    var lava = START.offset(2, 0, 2);
                    for (var side : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST))
                        world.setBlockAndUpdate(lava.relative(side), Blocks.STONE.defaultBlockState());
                    world.setBlockAndUpdate(lava, Blocks.LAVA.defaultBlockState());
                    wallReady = true;
                });
            }
            if (client.builder().state() != BuildSession.State.COMPLETE) return false;
            require(wallRequested && wallReady && mc.level.getBlockState(START.offset(3, 1, 0)).is(Blocks.STONE), "The obstacle must exist before the final placement is accepted");
            require(sideways >= 1.25, "The new two-high wall must force a genuine detour");
            require(mc.level.getBlockState(TARGET).is(Blocks.STONE) && client.builder().placementClicks() == 1, "The final block must be acknowledged once after the changed route");
            require(client.builder().maxPlacementDistance() <= 4.500001, "Replanning must preserve normal survival click reach");
            pass("Dynamic A* rerouted around a new two-high wall and lava in " + (ticks - phaseTick) + " ticks, travelling " + String.format(Locale.ROOT, "%.2f", travelled) + " blocks");
            setupOrientations(mc, client);
            phaseTick = ticks;
            stage = 2;
            return false;
        }
        if (stage == 2) {
            if (!fixtureReady || ticks - phaseTick < 20 || !mc.player.blockPosition().equals(ORIENTATION_START)
                || client.builder().inventory().getOrDefault("minecraft:piston", 0) != 1) return false;
            startDocument(mc, client, ORIENTED, "Real look rays for stairs, chest and piston");
            phaseTick = ticks;
            stage = 3;
            return false;
        }
        healthy(client, "Face-dependent vanilla placement");
        require(ticks - phaseTick <= 300, "Three reachable oriented blocks must not cause an endless viewpoint cycle");
        if (client.builder().state() != BuildSession.State.COMPLETE) return false;
        require(ORIENTED.entrySet().stream().allMatch(e -> mc.level.getBlockState(e.getKey()) == e.getValue()), "Stairs, chest and piston must have the exact requested server-confirmed facing and state");
        require(client.builder().placementClicks() == 3 && client.builder().inventory().isEmpty(), "Each oriented block must be placed once using its real survival item");
        require(client.builder().materials() != null && client.builder().materials().remainingItems() == 0, "Oriented material requirements must reach zero after confirmation");
        require(client.builder().maxPlacementDistance() <= 4.500001, "Oriented placements must also respect normal reach");
        pass("Real look rays and clicked faces built EAST stairs, NORTH chest and WEST piston with exact final states");
        return true;
    }

    private static void setupOrientations(Minecraft mc, AuroraClient client) {
        client.builder().stop("Preparing face-dependent placement regression");
        fixtureReady = false;
        var uuid = mc.player.getUUID();
        mc.getSingleplayerServer().execute(() -> {
            var world = mc.getSingleplayerServer().overworld();
            var player = mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
            for (int x = -5; x <= 9; x++) for (int z = -5; z <= 8; z++) {
                world.setBlockAndUpdate(ORIENTATION_START.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y <= 5; y++) world.setBlockAndUpdate(ORIENTATION_START.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.OAK_STAIRS));
            player.getInventory().setItem(1, new ItemStack(Items.CHEST));
            player.getInventory().setItem(2, new ItemStack(Items.PISTON));
            player.getInventory().setSelectedSlot(0);
            player.inventoryMenu.broadcastChanges();
            player.teleportTo(ORIENTATION_START.getX() + 0.5, ORIENTATION_START.getY(), ORIENTATION_START.getZ() + 0.5);
            fixtureReady = true;
        });
    }

    private static void startDocument(Minecraft mc, AuroraClient client, Map<BlockPos, BlockState> world, String name) {
        var anchor = mc.player.blockPosition().immutable();
        var relative = new HashMap<BlockPos, BlockState>();
        world.forEach((pos, state) -> relative.put(pos.subtract(anchor), state));
        var document = new DirectSchematic(name, Path.of("schematics/aurora-smoke/advanced-builder.litematic"), relative);
        client.builder().load(document);
        client.builder().startSnapshot(document.at(anchor));
    }
    private static void healthy(AuroraClient client, String phase) {
        var state = client.builder().state();
        if (state == BuildSession.State.PAUSED || state == BuildSession.State.MISSING_MATERIALS)
            throw new AssertionError(phase + " stopped: " + client.builder().status()+"; actual oriented states="+ORIENTED.entrySet().stream().map(e->e.getKey()+" wanted="+e.getValue()+" actual="+Minecraft.getInstance().level.getBlockState(e.getKey())).toList());
    }
    private static void require(boolean condition, String message) {if (!condition) throw new AssertionError(message);}
    private static void pass(String message) {AuroraClient.LOG.info("SMOKE PASS: {}", message);}
}
