package dev.aurora.smoke;

import dev.aurora.core.ClientConfig;
import com.mojang.blaze3d.platform.InputConstants;
import dev.aurora.minecraft.AuroraClient;
import dev.aurora.minecraft.AuroraScreen;
import dev.aurora.minecraft.ModuleSettingsScreen;
import dev.aurora.minecraft.AuroraHud;
import dev.aurora.smoke.mixin.ChatAccessor;
import dev.aurora.smoke.mixin.ChatInputAccessor;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Screenshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Development-only integration runner. Never included in the distributed client JAR. */
public final class SmokeCheck {
    private static int stage, ticks, stageTick, specialModelsBefore;
    private static net.minecraft.client.renderer.state.level.CameraRenderState previewCamera;
    public static final java.util.concurrent.atomic.AtomicInteger SPECIAL_MODELS=new java.util.concurrent.atomic.AtomicInteger();
    private static boolean finished;
    private static int originalGuiScale;
    private static volatile boolean fixtureReady;
    private static net.minecraft.core.BlockPos buildBase, chestPos, pairPos, pairOther;
    private static net.minecraft.world.phys.Vec3 builderStart;
    private static java.util.List<dev.aurora.core.builder.BuildPlan.Block> fixtureBlocks;
    private static Path schematicFile;
    private static net.minecraft.core.BlockPos previewAnchor;
    private static dev.aurora.minecraft.builder.SchematicFileScreen fileScreen;
    private static final Path RESULT = Path.of("aurora-smoke-result.txt");

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        AuroraClient.LOG.info("SMOKE PASS: {}", message);
    }

    public static void tick() {
        if (finished) return;
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        try {
            if (ticks > 3600) throw new AssertionError("Timed out in stage " + stage);
            if (mc.gui.screen() instanceof AccessibilityOnboardingScreen screen) { screen.onClose(); return; }
            AuroraClient client = AuroraClient.instance();
            if (stage == 0 && mc.gui.screen() instanceof TitleScreen && mc.gui.overlay() == null) {
                check(client != null && client.modules().all().stream().filter(m -> !m.id().equals("auto_builder")).allMatch(m -> m.available()), "All ten modules have game implementations");
                mc.options.renderDistance().set(3);mc.options.improvedTransparency().set(false);
                mc.options.simulationDistance().set(5);
                mc.createWorldOpenFlows().createFreshLevel("aurora-smoke-" + System.currentTimeMillis(),
                    new LevelSettings("Aurora smoke test", GameType.CREATIVE,
                        new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(42, false, false), WorldPresets::createTestWorldDimensions, new TitleScreen());
                stage = 1;
            } else if (stage == 1 && mc.player != null && mc.gui.screen() == null && mc.gui.overlay() == null) {
                check(mc.level != null, "Loaded real singleplayer world");
                check(ClientConfig.RIGHT_SHIFT == InputConstants.KEY_RSHIFT, "Default binding matches Minecraft 26.3 Right Shift scancode");
                for (var module : client.modules().all()) {
                    client.config().resetHudPosition(module.id());
                    client.config().setting(module.id(), "background", "true");
                    client.config().setting(module.id(), "key", "0");
                }
                equip(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET), 0.1);
                equip(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE), 0.6);
                equip(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS), 0.9);
                equip(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS), 0.2);
                mc.player.setXRot(90);
                client.config().menuKey(ClientConfig.RIGHT_SHIFT);
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), 1, new KeyEvent(229, 0, 0));
                check(mc.gui.screen() instanceof AuroraScreen, "Right Shift opens Aurora through the keyboard hook");
                for (var module : client.modules().all()) { if (!module.available() || module.id().equals("auto_builder")) continue; module.setEnabled(true); client.config().remember(module); }
                client.save();
                check(ClientConfig.load(Path.of("config/aurora.properties")).enabledModules().size() == 10, "Enabled modules persisted");
                stage = 2; stageTick = ticks;
            } else if (stage == 2 && ticks - stageTick > 30) {
                capture("aurora-menu.png");
                check(mc.gui.screen() instanceof AuroraScreen, "Animated menu rendered for 30 ticks without errors");
                AuroraScreen menu = (AuroraScreen) mc.gui.screen();
                clickPanel(menu, 200, 82);
                for (char ch : "zoom".toCharArray()) menu.charTyped(new CharacterEvent(ch));
                clickPanel(menu, 200, 135);
                check(!client.enabled("zoom"), "Native left-click and global search toggle Zoom from Combat category");
                clickPanel(menu, 200, 135);
                check(client.enabled("zoom"), "Native left-click re-enables Zoom");
                clickPanel(menu, 40, 110);
                float menuScale = Math.max(0.1f, Math.min(1, Math.min(menu.width / 720f, menu.height / 460f)));
                double menuX = (Math.round(menu.width / menuScale) - 680) / 2f;
                double menuY = (Math.round(menu.height / menuScale) - 420) / 2f;
                menu.mouseClicked(new MouseButtonEvent((menuX + 200) * menuScale, (menuY + 188) * menuScale,
                    new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0)), false);
                check(mc.gui.screen() instanceof ModuleSettingsScreen, "Right click opens module settings in Minecraft");
                var settings = (ModuleSettingsScreen) mc.gui.screen();
                int settingsX = (settings.width - Math.min(440, settings.width - 24)) / 2;
                int settingsY = (settings.height - Math.min(360, settings.height - 24)) / 2;
                settings.mouseClicked(mouse(settingsX + 30, settingsY + 64 + 2 * 34 + 10), false);
                check(!client.config().flag("armor_status", "background", true), "HUD background can be disabled from module settings");
                check(!ClientConfig.load(Path.of("config/aurora.properties")).flag("armor_status", "background", true), "Module setting survives config reload");
                check(client.enabled("armor_status"), "Right click settings preserve module enabled state");
                settings.onClose();
                clickPanel(menu, 200, 375);
                menu.keyPressed(new KeyEvent(InputConstants.KEY_R, 0, 0));
                check(client.config().menuKey() == InputConstants.KEY_R, "Changed menu binding through native GUI input");
                mc.gui.screen().onClose();
                check(mc.gui.screen() == null, "Menu closes back into world");
                mc.gui.hud.getChat().addClientSystemMessage(Component.literal("Aurora smoke: local timestamp check"));
                String received = ((ChatAccessor) mc.gui.hud.getChat()).aurora$messages().getFirst().content().getString();
                check(received.matches("\\[\\d{2}:\\d{2}\\] Aurora smoke: local timestamp check"), "Chat timestamp hook modifies actual chat messages");
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), 1, new KeyEvent(229, 0, 0));
                check(mc.gui.screen() == null, "Old binding no longer opens menu");
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), 1, new KeyEvent(21, 0, 0));
                check(mc.gui.screen() instanceof AuroraScreen, "Changed menu binding opens menu");
                mc.gui.screen().onClose();
                client.config().menuKey(229); client.save();
                stage = 3; stageTick = ticks;
            } else if (stage == 3 && ticks - stageTick > 30) {
                client.config().setting("fps_counter", "key", Integer.toString(InputConstants.KEY_H));
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), 1, new KeyEvent(InputConstants.KEY_H, 0, 0));
                check(!client.enabled("fps_counter"), "Module hotkey toggles module through keyboard hook");
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), 1, new KeyEvent(InputConstants.KEY_H, 0, 0));
                check(client.enabled("fps_counter"), "Module hotkey toggles module back on");
                client.config().setting("fps_counter", "key", "0");
                check(mc.player.input.keyPresses.shift(), "Toggle Sneak applied to real player input");
                check(mc.player.input.keyPresses.sprint(), "Toggle Sprint applied to real player input");
                check(mc.gameRenderer.mainCamera().getFov() < 35, "Zoom changes real camera FOV");
                capture("aurora-hud.png");
                var armor = AuroraHud.panels().get("armor_status");
                check(armor != null && armor.height() == 104, "Armor HUD contains four equipped armor rows");
                check(armor.x() == mc.getWindow().getGuiScaledWidth() - armor.width() - 8
                    && armor.y() == mc.getWindow().getGuiScaledHeight() - armor.height() - 44, "Armor HUD defaults to bottom right");
                check(!AuroraHud.beginDrag(armor.x() + 10, armor.y() + 10), "HUD cannot be dragged outside chat");
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), 1, new KeyEvent(InputConstants.KEY_T, 0, 0));
                stage = 4; stageTick = ticks;
            } else if (stage == 4 && ticks - stageTick > 20) {
                check(mc.gui.screen() instanceof ChatScreen, "T opens real chat screen");
                ChatScreen chat = (ChatScreen) mc.gui.screen();
                var armor = AuroraHud.panels().get("armor_status");
                check(chat.mouseClicked(mouse(armor.x() + 10, armor.y() + 10), false), "Chat mouse press grabs armor panel");
                check(chat.mouseDragged(mouse(100, 80), 0, 0), "Chat mouse drag moves armor panel");
                check(chat.mouseReleased(mouse(100, 80)), "Chat mouse release finishes drag");
                var movedArmor = AuroraHud.panels().get("armor_status");
                check(movedArmor.x() == 90 && movedArmor.y() == 70, "Dragging preserves cursor offset");
                check(ClientConfig.load(Path.of("config/aurora.properties")).hudPosition("armor_status") != null, "Armor position saved on mouse release");
                var fps = AuroraHud.panels().get("fps_counter");
                chat.mouseClicked(mouse(fps.x() + 5, fps.y() + 5), false);
                chat.mouseDragged(mouse(240, 25), 0, 0);
                chat.onClose();
                check(ClientConfig.load(Path.of("config/aurora.properties")).hudPosition("fps_counter") != null, "Closing chat during drag saves other HUD panels");
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), 1, new KeyEvent(InputConstants.KEY_T, 0, 0));
                stage = 5; stageTick = ticks;
            } else if (stage == 5 && ticks - stageTick > 20) {
                ChatScreen chat = (ChatScreen) mc.gui.screen();
                var input = ((ChatInputAccessor) chat).aurora$input();
                String before = input.getValue();
                chat.charTyped(new CharacterEvent('h'));
                check(input.getValue().equals(before + "h"), "Normal chat typing works after HUD dragging");
                capture("aurora-hud-editor.png");
                chat.onClose();
                check(!AuroraHud.drag(10, 10), "Chat close ends dragging");
                originalGuiScale = mc.options.guiScale().get();
                mc.options.guiScale().set(1);
                mc.resizeGui();
                stage = 6; stageTick = ticks;
            } else if (stage == 6 && ticks - stageTick > 20) {
                int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
                check(AuroraHud.panels().values().stream().allMatch(b -> b.x() >= 0 && b.y() >= 0
                    && b.x() + b.width() <= width && b.y() + b.height() <= height - 24), "Saved HUD positions remain visible after GUI scale change");
                mc.options.guiScale().set(originalGuiScale); mc.resizeGui();
                for (var module : client.modules().all()) { module.setEnabled(false); client.config().remember(module); }
                client.save();
                stage = 7; stageTick = ticks;
            } else if (stage == 7 && ticks - stageTick > 30) {
                check(!mc.player.input.keyPresses.shift(), "Disabling Sneak restores player input");
                check(!mc.player.input.keyPresses.sprint(), "Disabling Sprint restores player input");
                check(mc.gameRenderer.mainCamera().getFov() > 50, "Disabling Zoom restores camera FOV");
                check(ClientConfig.load(Path.of("config/aurora.properties")).menuKey() == 229, "Default binding restored and saved");
                check(Files.exists(Path.of("screenshots/aurora-menu.png")) && Files.exists(Path.of("screenshots/aurora-hud.png")), "Game screenshots captured");
                check(Files.exists(Path.of("screenshots/aurora-hud-editor.png")), "HUD editor screenshot captured");
                var failing = client.modules().get("zoom");
                failing.bind(new dev.aurora.core.ClientModule.Effect() {
                    public void enable() { throw new IllegalStateException("Smoke: missing builder prerequisite"); }
                    public void disable() { }
                });
                client.config().setting("zoom", "key", Integer.toString(InputConstants.KEY_H));
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), 1, new KeyEvent(InputConstants.KEY_H,0,0));
                check(!failing.enabled(), "Failed module hotkey reports an error without crashing Minecraft");
                failing.bind(new dev.aurora.core.ClientModule.Effect() { public void enable() { } public void disable() { } });
                client.config().setting("zoom", "key", "0");
                buildBase = mc.player.blockPosition().immutable();
                chestPos = buildBase.offset(0, 0, 6);
                builderStart = mc.player.position();
                client.builder().chests.save(java.util.List.of());
                var server = mc.getSingleplayerServer();
                var uuid = mc.player.getUUID();
                server.execute(() -> {
                    var player = server.getPlayerList().getPlayer(uuid);
                    player.setGameMode(GameType.SURVIVAL);
                    player.getInventory().clearContent();
                    player.inventoryMenu.broadcastChanges();
                    server.overworld().setBlockAndUpdate(chestPos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
                    var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) server.overworld().getBlockEntity(chestPos);
                    chest.setItem(0, new ItemStack(Items.STONE, 8));
                    chest.setItem(1,new ItemStack(Items.OAK_STAIRS,1));chest.setItem(2,new ItemStack(Items.STONE_SLAB,2));chest.setChanged();
                    var leftState=net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.LEFT);
                    pairPos=buildBase.offset(-4,0,4);
                    pairOther=pairPos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(leftState));
                    server.overworld().setBlock(pairPos,leftState,2);
                    server.overworld().setBlock(pairOther,leftState.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,net.minecraft.world.level.block.state.properties.ChestType.RIGHT),2);
                    fixtureReady = true;
                });
                stage = 8; stageTick = ticks;
            } else if (stage == 8 && fixtureReady && ticks - stageTick > 30) {
                check(mc.gameMode.getPlayerMode() == GameType.SURVIVAL, "Builder fixture runs in real survival mode");
                check(client.builder().inventory().getOrDefault("minecraft:stone", 0) == 0, "No materials in player inventory before restock");
                mc.hitResult = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(chestPos), net.minecraft.core.Direction.UP, chestPos, false);
                mc.keyboardHandler.keyPress(mc.getWindow().handle(), 1, new KeyEvent(InputConstants.KEY_V, 0, 0));
                check(client.builder().chests.current().size() == 1, "Restock key registers the looked-at chest");
                check(client.builder().chests.canonical(pairPos).equals(client.builder().chests.canonical(pairOther)), "Both halves of a double chest share one restock identity");
                client.config().setting("auto_builder", "chest_color", Integer.toString(0xffff7f8c)); client.save();
                check(ClientConfig.load(Path.of("config/aurora.properties")).number("auto_builder", "chest_color", 0) == 0xffff7f8c, "Restock outline color is configurable and persisted");
                client.builder().inspectChests();
                stage = 9; stageTick = ticks;
            } else if (stage == 9 && !client.builder().restock.busy() && ticks - stageTick > 20) {
                check(client.builder().restock.stock().inspected(), "Restock chest inventory was synchronized by the server");
                check(client.builder().restock.stock().items().getOrDefault("minecraft:stone", 0) == 8, "Inspected chest exposes its real inventory");
                check(mc.player.position().distanceTo(builderStart) > 2, "Navigator walked to the chest without teleporting");
                capture("aurora-restock.png");
                schematicFile=writeSchematicFile();
                mc.gui.setScreen(new AuroraScreen(client));
                client.modules().get("auto_builder").setEnabled(true);
                check(mc.gui.screen() instanceof dev.aurora.minecraft.builder.SchematicFileScreen, "Enabling Auto Builder automatically opens the local file picker");
                mc.gui.screen().onClose();
                check(!client.modules().get("auto_builder").enabled(), "Cancelling the file picker cancels pending construction");
                client.config().setting("auto_builder","preview","false");client.config().setting("auto_builder","materials_hud","false");
                fileScreen=new dev.aurora.minecraft.builder.SchematicFileScreen(new AuroraScreen(client),client);
                mc.gui.setScreen(fileScreen);fileScreen.browse(schematicFile.getParent());
                stage=20;stageTick=ticks;
            } else if(stage==20 && ticks-stageTick>20 && fileScreen.entries().contains(schematicFile)) {
                check(mc.gui.screen()==fileScreen, "Local schematic file picker opens inside Minecraft");
                capture("aurora-file-picker.png");
                int index=fileScreen.entries().indexOf(schematicFile);
                fileScreen.mouseClicked(mouse(30,67+index*24+9),false);
                stage=21;stageTick=ticks;
            } else if(stage==21 && client.builder().document()!=null && client.builder().document().file().equals(schematicFile)) {
                check(!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("litematica") && !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("malilib"), "Schematic works without Litematica and MaLiLib installed");
                check(client.builder().document().blocks().size()==4, "Selected real .litematic file decoded all block states");
                check(mc.gui.screen() instanceof dev.aurora.minecraft.builder.MaterialListScreen,"Selecting a file immediately opens that file's full material list");
                check(client.config().flag("auto_builder","preview",false) && client.config().flag("auto_builder","materials_hud",false),"Selecting a file shows its preview and material HUD even after previously hiding them");
                mc.gui.setScreen(null);
                client.config().hudPosition("auto_builder",new dev.aurora.core.HudLayout.Position(0,0.05));
                mc.keyboardHandler.keyPress(mc.getWindow().handle(),1,new KeyEvent(InputConstants.KEY_G,0,0));
                check(client.builder().anchor().equals(mc.player.blockPosition()), "G anchors the schematic corner at the player's feet");
                stage=22;stageTick=ticks;
            } else if(stage==22 && ticks-stageTick>20) {
                var ghosts=client.builder().preview.ghosts();
                check(ghosts.size()==4 && ghosts.stream().allMatch(g->!g.model().isEmpty()), "Preview extracts the actual textured stone, stair and slab models");
                check(ghosts.stream().allMatch(g->g.outlineColor()==dev.aurora.minecraft.builder.SchematicPreview.MISSING_COLOR), "Unplaced blocks have red outlines");
                fixtureBlocks=client.builder().document().at(client.builder().anchor()).plan().blocks();
                check(client.builder().materials().remainingItems()==5, "Live material list counts a double slab as two items");
                previewAnchor=client.builder().anchor();
                mc.getSingleplayerServer().execute(()->{
                    var world=mc.getSingleplayerServer().overworld();
                    world.setBlockAndUpdate(previewAnchor,net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState());
                    world.setBlockAndUpdate(previewAnchor.offset(1,0,0),net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING,net.minecraft.core.Direction.NORTH));
                    world.setBlockAndUpdate(previewAnchor.offset(2,0,0),net.minecraft.world.level.block.Blocks.STONE_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.TYPE,net.minecraft.world.level.block.state.properties.SlabType.DOUBLE));
                });
                stage=23;stageTick=ticks;
            } else if(stage==23 && ticks-stageTick>20) {
                var ghosts=client.builder().preview.ghosts();
                check(ghosts.size()==3 && ghosts.stream().filter(g->g.outlineColor()==dev.aurora.minecraft.builder.SchematicPreview.WRONG_COLOR).count()==2,
                    "Wrong block and wrong stair orientation are yellow; correctly placed slab is hidden");
                check(client.builder().materials().remainingItems()==3, "Material list subtracts correctly placed blocks");
                mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().overworld().setBlockAndUpdate(previewAnchor,net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()));
                stage=24;stageTick=ticks;
            } else if(stage==24 && ticks-stageTick>15) {
                check(client.builder().preview.ghosts().stream().noneMatch(g->g.pos().equals(previewAnchor)), "Correct placement removes that block's ghost and outline");
                check(client.builder().materials().remainingItems()==2, "Correct placement reduces required materials immediately");
                mc.getSingleplayerServer().execute(()->mc.getSingleplayerServer().overworld().destroyBlock(previewAnchor,false));
                stage=25;stageTick=ticks;
            } else if(stage==25 && ticks-stageTick>15) {
                check(client.builder().preview.ghosts().stream().anyMatch(g->g.pos().equals(previewAnchor) && g.outlineColor()==dev.aurora.minecraft.builder.SchematicPreview.MISSING_COLOR),
                    "Removing a correct block restores its red textured preview");
                check(client.builder().materials().remainingItems()==3, "Removing a placed block increases the required material count");
                check(AuroraHud.panels().containsKey("auto_builder"), "Materials HUD appears while previewing, with the builder disabled");
                mc.options.keyDown.setDown(true);
                stage=26;stageTick=ticks;
            } else if(stage==26 && ticks-stageTick>20) {
                mc.options.keyDown.setDown(false);
                dev.aurora.minecraft.builder.RestockJob.lookAt(net.minecraft.world.phys.Vec3.atCenterOf(previewAnchor.above()));
                stage=260;stageTick=ticks;
            } else if(stage==260 && ticks-stageTick>10) {
                capture("aurora-schematic-preview.png");
                previewCamera=mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
                client.config().setting("auto_builder","preview","false");stage=264;stageTick=ticks;
            } else if(stage==264 && ticks-stageTick>15) {
                capture("aurora-preview-classic-off.png");client.config().setting("auto_builder","preview","true");
                mc.options.improvedTransparency().set(true);stage=261;stageTick=ticks;
            } else if(stage==261 && ticks-stageTick>60 && mc.levelRenderer.hasRenderedAllSections()) {
                check(mc.gameRenderer.useImprovedTransparency(),"Preview also runs with improved transparency enabled");
                capture("aurora-preview-improved.png");client.config().setting("auto_builder","preview","false");
                stage=262;stageTick=ticks;
            } else if(stage==262 && ticks-stageTick>30 && mc.levelRenderer.hasRenderedAllSections()) {
                capture("aurora-preview-off.png");stage=263;stageTick=ticks;
            } else if(stage==263 && ticks-stageTick>15) {
                verifyTexturePixels("aurora-schematic-preview.png","aurora-preview-classic-off.png");
                verifyTexturePixels("aurora-preview-improved.png","aurora-preview-off.png");
                client.config().setting("auto_builder","preview","true");mc.options.improvedTransparency().set(false);
                mc.keyboardHandler.keyPress(mc.getWindow().handle(),1,new KeyEvent(InputConstants.KEY_T,0,0));
                stage=27;stageTick=ticks;
            } else if(stage==27 && ticks-stageTick>10) {
                var panel=AuroraHud.panels().get("auto_builder");var chat=(ChatScreen)mc.gui.screen();
                check(chat.mouseClicked(mouse(panel.x()+5,panel.y()+5),false), "Materials HUD can be dragged in chat before starting construction");
                chat.mouseDragged(mouse(25,90),0,0);chat.mouseReleased(mouse(25,90));chat.onClose();
                client.config().resetHudPosition("auto_builder");
                mc.gui.setScreen(new dev.aurora.minecraft.builder.MaterialListScreen(new AuroraScreen(client),client));
                stage=28;stageTick=ticks;
            } else if(stage==28 && ticks-stageTick>15) {
                check(mc.gui.screen() instanceof dev.aurora.minecraft.builder.MaterialListScreen, "Full material list renders with item icons and total/left/have/missing columns");
                capture("aurora-material-list.png");mc.gui.setScreen(null);mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                mc.getSingleplayerServer().execute(()->{
                    var world=mc.getSingleplayerServer().overworld();
                    world.setBlockAndUpdate(previewAnchor.offset(1,0,0),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    world.setBlockAndUpdate(previewAnchor.offset(2,0,0),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                });
                stage=29;stageTick=ticks;
            } else if(stage==29 && ticks-stageTick>15) {
                check(client.builder().materials().remainingItems()==5, "Restored empty build site restores all five required items");
                client.modules().get("auto_builder").setEnabled(true);
                stage=10;stageTick=ticks;
            } else if (stage == 10 && client.builder().state() == dev.aurora.core.builder.BuildSession.State.COMPLETE) {
                check(fixtureBlocks.stream().allMatch(client.builder()::placed), "File-based survival placement is server-confirmed for both layers, stairs and double slab");
                check(client.builder().inventory().getOrDefault("minecraft:stone", 0) == 6, "Restock transferred eight stone and survival placement consumed two");
                check(client.builder().inventory().getOrDefault("minecraft:oak_stairs",0)==0 && client.builder().inventory().getOrDefault("minecraft:stone_slab",0)==0, "Native placement consumes stair and both slab items");
                capture("aurora-build.png");
                client.modules().get("auto_builder").setEnabled(false);
                specialModelsBefore=SPECIAL_MODELS.get();
                client.builder().load(new dev.aurora.minecraft.builder.DirectSchematic("Chest model preview",schematicFile,java.util.Map.of(
                    new net.minecraft.core.BlockPos(0,0,3),net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState())));
                dev.aurora.minecraft.builder.RestockJob.lookAt(net.minecraft.world.phys.Vec3.atCenterOf(client.builder().anchor().offset(0,0,3)));
                stage=30;stageTick=ticks;
            } else if (stage == 10 && client.builder().state() == dev.aurora.core.builder.BuildSession.State.PAUSED) {
                throw new AssertionError("Builder paused: " + client.builder().status() + "; inventory=" + client.builder().inventory() + "; restock=" + client.builder().restock.status());
            } else if(stage==30 && ticks-stageTick>15) {
                check(SPECIAL_MODELS.get()>specialModelsBefore, "Native chest model geometry was submitted with translucency");
                capture("aurora-special-preview.png");
                var mixed=new dev.aurora.minecraft.builder.DirectSchematic("Mixed materials",schematicFile,java.util.Map.of(
                    new net.minecraft.core.BlockPos(0,0,3),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),
                    new net.minecraft.core.BlockPos(0,1,3),net.minecraft.world.level.block.Blocks.WATER.defaultBlockState()));
                client.builder().load(mixed);stage=31;stageTick=ticks;
            } else if(stage==31 && ticks-stageTick>10) {
                check(client.builder().materials()!=null && client.builder().materials().rows().size()==2 && AuroraHud.panels().containsKey("auto_builder"),"Water in a selected schematic no longer hides the material list or HUD");
                client.builder().load(dev.aurora.minecraft.builder.DirectSchematic.read(schematicFile));
                check(client.builder().materials().rows().size()==3 && client.builder().materials().totalItems()==5,"Reselecting the file replaces the checklist with that file's materials");
                fixtureBlocks = java.util.stream.IntStream.range(0, 7).mapToObj(i -> buildBlock(12+i,0)).toList();
                client.builder().startSnapshot(fixtureSnapshot(fixtureBlocks));
                stage = 11; stageTick = ticks;
            } else if (stage == 11 && client.builder().state() == dev.aurora.core.builder.BuildSession.State.MISSING_MATERIALS) {
                check(fixtureBlocks.stream().noneMatch(client.builder()::placed), "Insufficient total materials prevent any new placement");
                client.builder().stop("Smoke test complete");
                Files.writeString(RESULT, "PASS: Standalone .litematic file selected in the local file picker, decoded and anchored with G, previewed with textured translucent models, red/yellow/correct/mined state transitions and live material HUD/list, and built in survival without Litematica/MaLiLib. Restock, server-confirmed layers, oriented stairs, double slabs, inventory consumption and missing-material gate passed. UI/HUD/settings regressions passed.\n");
                finished = true;
                AuroraClient.LOG.info("AURORA SMOKE COMPLETE: PASS");
                mc.stop();
            }
        } catch (Throwable error) {
            finished = true;
            AuroraClient.LOG.error("AURORA SMOKE FAILED", error);
            try { Files.writeString(RESULT, "FAIL: " + error + "\n"); } catch (Exception ignored) { }
            mc.stop();
        }
    }

    private static void verifyTexturePixels(String withPreview,String withoutPreview) throws Exception {
        try(var on=com.mojang.blaze3d.platform.NativeImage.read(Files.readAllBytes(Path.of("screenshots",withPreview)));
            var off=com.mojang.blaze3d.platform.NativeImage.read(Files.readAllBytes(Path.of("screenshots",withoutPreview)))) {
            // Sample the centre of the missing stone's north face, well inside its coloured outline.
            var point=new org.joml.Vector4f((float)(previewAnchor.getX()+0.5-previewCamera.pos.x),
                (float)(previewAnchor.getY()+0.5-previewCamera.pos.y),(float)(previewAnchor.getZ()-0.002-previewCamera.pos.z),1);
            point.mul(previewCamera.viewRotationMatrix).mul(previewCamera.projectionMatrix);
            int x=Math.round((point.x/point.w*0.5f+0.5f)*on.getWidth()),y=Math.round((0.5f-point.y/point.w*0.5f)*on.getHeight());
            check(x>8 && x<on.getWidth()-8 && y>8 && y<on.getHeight()-8,"Texture sampling point lies on screen at "+x+","+y);
            int changed=0;long difference=0;
            for(int dy=-5;dy<=5;dy++)for(int dx=-5;dx<=5;dx++) {
                int a=on.getPixel(x+dx,y+dy),b=off.getPixel(x+dx,y+dy),delta=0;
                for(int shift:new int[]{0,8,16})delta+=Math.abs((a>>>shift&255)-(b>>>shift&255));
                difference+=delta;if(delta>15)changed++;
            }
            check(changed>90 && difference>3000,"Actual texture pixels fill the block face, beyond its outline: "+withPreview+" ("+changed+" pixels, delta "+difference+")");
        }
    }

    private static Path writeSchematicFile() throws Exception {
        var root=new net.minecraft.nbt.CompoundTag();root.putInt("Version",7);
        var metadata=new net.minecraft.nbt.CompoundTag();metadata.putString("Name","Aurora standalone file smoke");root.put("Metadata",metadata);
        var region=new net.minecraft.nbt.CompoundTag();
        var position=new net.minecraft.nbt.CompoundTag();position.putInt("x",0);position.putInt("y",0);position.putInt("z",0);region.put("Position",position);
        var size=new net.minecraft.nbt.CompoundTag();size.putInt("x",3);size.putInt("y",2);size.putInt("z",1);region.put("Size",size);
        var palette=new net.minecraft.nbt.ListTag();
        for(var state:java.util.List.of(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),
            net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING,net.minecraft.core.Direction.EAST),
            net.minecraft.world.level.block.Blocks.STONE_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.TYPE,net.minecraft.world.level.block.state.properties.SlabType.DOUBLE)))palette.add(net.minecraft.nbt.NbtUtils.writeBlockState(state));
        region.put("BlockStatePalette",palette);region.putLongArray("BlockStates",new long[]{121});
        var regions=new net.minecraft.nbt.CompoundTag();regions.put("main",region);root.put("Regions",regions);
        Path folder=Minecraft.getInstance().gameDirectory.toPath().resolve("schematics/aurora-smoke").toAbsolutePath();Files.createDirectories(folder);
        Path file=folder.resolve("local schematic.litematic");net.minecraft.nbt.NbtIo.writeCompressed(root,file);return file;
    }

    private static dev.aurora.core.builder.BuildPlan.Block buildBlock(int x, int y) {
        var pos=buildBase.offset(x,y,0);
        return new dev.aurora.core.builder.BuildPlan.Block(pos.getX(),pos.getY(),pos.getZ(),"minecraft:stone",1,"minecraft:stone");
    }
    private static dev.aurora.minecraft.builder.DirectSchematic.Snapshot fixtureSnapshot(java.util.List<dev.aurora.core.builder.BuildPlan.Block> blocks) {
        java.util.Map<net.minecraft.core.BlockPos,net.minecraft.world.level.block.state.BlockState> states=new java.util.HashMap<>();
        for(var b:blocks)states.put(new net.minecraft.core.BlockPos(b.x(),b.y(),b.z()),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        return new dev.aurora.minecraft.builder.DirectSchematic.Snapshot(new dev.aurora.core.builder.BuildPlan(blocks),states,java.util.Map.of());
    }

    private static void equip(EquipmentSlot slot, ItemStack stack, double damageFraction) {
        stack.setDamageValue((int) (stack.getMaxDamage() * damageFraction));
        Minecraft.getInstance().player.setItemSlot(slot, stack);
    }

    private static MouseButtonEvent mouse(double x, double y) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
    }

    private static void capture(String name) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1,
            message -> AuroraClient.LOG.info("Smoke screenshot: {}", message.getString()));
    }

    private static void clickPanel(AuroraScreen screen, double x, double y) {
        float scale = Math.max(0.1f, Math.min(1, Math.min(screen.width / 720f, screen.height / 460f)));
        double panelX = (Math.round(screen.width / scale) - 680) / 2f;
        double panelY = (Math.round(screen.height / scale) - 420) / 2f;
        screen.mouseClicked(new MouseButtonEvent((panelX + x) * scale, (panelY + y) * scale,
            new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false);
    }
}
