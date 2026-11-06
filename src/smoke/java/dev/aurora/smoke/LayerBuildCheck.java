package dev.aurora.smoke;

import dev.aurora.core.builder.BuildPlan;
import dev.aurora.core.builder.BuildSession;
import dev.aurora.minecraft.AuroraClient;
import dev.aurora.minecraft.builder.DirectSchematic;
import dev.aurora.minecraft.builder.RestockChests;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Native survival regression for limited inventory, deferred supports and exact layer boundaries. */
public final class LayerBuildCheck {
    private static final BlockPos BASE=new BlockPos(-384,-60,-628);
    private static final BlockPos START=BASE.offset(-2,0,-1);
    private static final BlockPos RESTOCK=BASE.offset(0,0,-4);
    private static final BlockPos CHEST_SUPPORT=BASE.offset(10,0,6);
    private static final Map<BlockPos,BlockState> expected=new LinkedHashMap<>();
    private static volatile boolean fixtureReady,negativeReady;
    private static int phaseTicks;
    private static final BlockPos IMPOSSIBLE=BASE.offset(11,0,0),FORBIDDEN_UPPER=IMPOSSIBLE.above();
    private static DirectSchematic.Snapshot snapshot;
    private static List<BuildPlan.Block> lower,upper;
    private static int ticks,stage,previousStone,stoneRefills,verifiedBatches;
    private static boolean previousRestock,firstCarryPlaced;

    private LayerBuildCheck() { }
    public static void start(Minecraft mc,AuroraClient client) {
        client.modules().get("auto_builder").setEnabled(false);
        client.builder().stop("Preparing inventory-capacity regression");
        mc.gui.setScreen(null);
        fixtureReady=false;negativeReady=false;phaseTicks=0;snapshot=null;ticks=0;stage=0;previousStone=1;stoneRefills=0;verifiedBatches=0;
        previousRestock=false;firstCarryPlaced=false;expected.clear();
        for(int z=0;z<8;z++)for(int x=0;x<9;x++) {
            int index=z*9+x;
            expected.put(BASE.offset(x,0,z),(index<70?Blocks.STONE:Blocks.GLASS).defaultBlockState());
        }
        // Sorted first, but it needs the stone to its east to be placed within this same upper layer.
        expected.put(BASE.offset(0,1,0),Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING,Direction.WEST));
        expected.put(BASE.offset(1,1,0),Blocks.STONE.defaultBlockState());
        expected.put(CHEST_SUPPORT.above(),Blocks.STONE.defaultBlockState());
        var uuid=mc.player.getUUID();
        mc.getSingleplayerServer().execute(()->{
            var server=mc.getSingleplayerServer();var world=server.overworld();var player=server.getPlayerList().getPlayer(uuid);
            player.setGameMode(GameType.SURVIVAL);
            // Only the test fixture changes the world or teleports. The builder must walk and place everything below.
            for(int x=-5;x<=13;x++)for(int z=-6;z<=10;z++) {
                world.setBlockAndUpdate(BASE.offset(x,-1,z),Blocks.STONE.defaultBlockState());
                for(int y=0;y<=5;y++)world.setBlockAndUpdate(BASE.offset(x,y,z),Blocks.AIR.defaultBlockState());
            }
            world.setBlockAndUpdate(RESTOCK,Blocks.CHEST.defaultBlockState());
            world.setBlockAndUpdate(CHEST_SUPPORT,Blocks.CHEST.defaultBlockState());
            var chest=(ChestBlockEntity)world.getBlockEntity(RESTOCK);
            chest.setItem(0,new ItemStack(Items.STONE,64));chest.setItem(1,new ItemStack(Items.STONE,7));
            chest.setItem(2,new ItemStack(Items.GLASS,2));chest.setItem(3,new ItemStack(Items.TORCH));chest.setChanged();
            player.getInventory().clearContent();
            player.getInventory().setItem(0,new ItemStack(Items.STONE));
            for(int slot=1;slot<36;slot++)player.getInventory().setItem(slot,new ItemStack(Items.COBBLESTONE,64));
            player.getInventory().setSelectedSlot(0);player.inventoryMenu.broadcastChanges();
            player.teleportTo(START.getX()+0.5,START.getY(),START.getZ()+0.5);
            fixtureReady=true;
        });
    }

    public static boolean tick(Minecraft mc,AuroraClient client) {
        if(++ticks>2200)throw new AssertionError("Layer-capacity test timed out: stage="+stage+", status="+client.builder().status());
        if(stage==2) {
            if(++phaseTicks<12)return false;
            try {net.minecraft.client.Screenshot.grab(mc.gameDirectory,"aurora-layer-success.png",mc.gameRenderer.mainRenderTarget(),1,message->AuroraClient.LOG.info("Smoke screenshot: {}",message.getString()));}
            catch(Exception error){throw new AssertionError("Could not capture successful capacity build",error);}
            client.builder().stop("Preparing impossible-support safety check");negativeReady=false;var uuid=mc.player.getUUID();
            mc.getSingleplayerServer().execute(()->{
                var world=mc.getSingleplayerServer().overworld();var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                world.setBlockAndUpdate(IMPOSSIBLE,Blocks.AIR.defaultBlockState());world.setBlockAndUpdate(IMPOSSIBLE.east(),Blocks.AIR.defaultBlockState());world.setBlockAndUpdate(FORBIDDEN_UPPER,Blocks.AIR.defaultBlockState());
                for(int i=0;i<36;i++)if(player.getInventory().getItem(i).isEmpty()){player.getInventory().setItem(i,new ItemStack(Items.TORCH));break;}
                var chest=(ChestBlockEntity)world.getBlockEntity(RESTOCK);for(int i=0;i<27;i++)chest.setItem(i,ItemStack.EMPTY);chest.setItem(0,new ItemStack(Items.STONE));chest.setChanged();
                player.inventoryMenu.broadcastChanges();negativeReady=true;
            });
            stage=3;return false;
        }
        if(stage==3) {
            if(!negativeReady || client.builder().inventory().getOrDefault("minecraft:torch",0)!=1)return false;
            var anchor=mc.player.blockPosition().immutable();
            var states=Map.of(IMPOSSIBLE.subtract(anchor),Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING,Direction.WEST),FORBIDDEN_UPPER.subtract(anchor),Blocks.STONE.defaultBlockState());
            var doc=new DirectSchematic("Missing support must stop the current layer",Path.of("schematics/aurora-smoke/missing-support.litematic"),states);
            client.builder().load(doc);client.builder().restock.invalidate();client.builder().startSnapshot(doc.at(anchor));stage=4;return false;
        }
        if(stage==4) {
            var builder=client.builder();
            require(mc.level.getBlockState(IMPOSSIBLE).isAir() && mc.level.getBlockState(FORBIDDEN_UPPER).isAir(),"An impossible lower placement never permits the upper layer to be built");
            require(builder.inventory().equals(Map.of("minecraft:cobblestone",35*64,"minecraft:torch",1)),"Missing-support handling consumes no build materials or filler items");
            if(builder.state()==BuildSession.State.MISSING_MATERIALS || builder.state()==BuildSession.State.COMPLETE)throw new AssertionError("Impossible support returned the wrong final state: "+builder.status());
            if(builder.state()!=BuildSession.State.PAUSED)return false;
            require(builder.status().toLowerCase(Locale.ROOT).contains("layer") && builder.status().toLowerCase(Locale.ROOT).contains("support"),"Impossible support pauses with an actionable layer/support explanation");
            pass("Missing support safely pauses the current layer without placing a higher block or consuming items");return true;
        }
        if(stage==0) {
            if(!fixtureReady || ticks<20 || !mc.player.blockPosition().equals(START) || !mc.level.hasChunkAt(BASE) || !mc.level.getBlockState(RESTOCK).is(Blocks.CHEST))return false;
            var relative=new HashMap<BlockPos,BlockState>();expected.forEach((pos,state)->relative.put(pos.subtract(START),state));
            var document=new DirectSchematic("72-block layer, one usable slot",Path.of("schematics/aurora-smoke/layer-capacity.litematic"),relative);
            client.builder().load(document);
            require(client.builder().anchor().equals(START),"Capacity fixture schematic uses actual player feet");
            snapshot=document.at(START);
            lower=snapshot.plan().blocks().stream().filter(b->b.y()==BASE.getY()).toList();
            upper=snapshot.plan().blocks().stream().filter(b->b.y()>BASE.getY()).toList();
            require(lower.size()==72 && upper.size()==3,"Capacity fixture has 72 lower actions and 3 dependent upper actions");
            require(client.builder().inventory().getOrDefault("minecraft:cobblestone",0)==35*64 && client.builder().inventory().getOrDefault("minecraft:stone",0)==1,"Survival inventory has 35 full filler stacks and only one carried stone");
            client.builder().chests.save(List.of(new RestockChests.Chest(client.builder().chests.dimension(),RESTOCK)));
            client.builder().restock.invalidate();client.builder().startSnapshot(snapshot);stage=1;
            return false;
        }
        var builder=client.builder();
        if(builder.state()==BuildSession.State.PAUSED || builder.state()==BuildSession.State.MISSING_MATERIALS)
            throw new AssertionError("Layer-capacity builder stopped: "+builder.status()+"; inventory="+builder.inventory());
        long lowerDone=lower.stream().filter(builder::placed).count();
        if(lowerDone>0 && !firstCarryPlaced) {firstCarryPlaced=true;pass("The initially carried stone is placed before fetching a layer-sized inventory");}
        int stone=builder.inventory().getOrDefault("minecraft:stone",0);
        if(stone>previousStone) {
            require(firstCarryPlaced,"Builder must consume usable carried material before its first restock transfer");
            stoneRefills++;
        }
        previousStone=stone;
        boolean restocking=builder.restock.busy();
        if(previousRestock && !restocking && builder.restock.status().equals("Restock complete"))verifiedBatches++;
        previousRestock=restocking;
        if(lowerDone<lower.size())require(upper.stream().noneMatch(builder::placed),"No upper-layer placement before the lower layer is server-confirmed");
        if(builder.state()!=BuildSession.State.COMPLETE)return false;
        require(firstCarryPlaced && stoneRefills>=2 && verifiedBatches>=2,"A layer larger than the only usable slot completed through multiple verified partial restocks");
        require(snapshot.plan().blocks().stream().allMatch(builder::placed),"Every layer action was acknowledged by the server");
        require(expected.entrySet().stream().allMatch(e->mc.level.getBlockState(e.getKey())==e.getValue()),"All lower and upper final states match, including deferred wall torch and solid block above a chest");
        require(builder.inventory().equals(Map.of("minecraft:cobblestone",35*64)),"Exactly the build materials were consumed and all 35 filler stacks were retained");
        require(builder.materials()!=null && builder.materials().remainingItems()==0,"Material HUD reaches zero after the entire bounded-inventory build");
        pass("Inventory-capacity build complete: "+stoneRefills+" stone refills and "+verifiedBatches+" verified partial batches");
        stage=2;phaseTicks=0;return false;
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    private static void pass(String message) {AuroraClient.LOG.info("SMOKE PASS: {}",message);}
}
