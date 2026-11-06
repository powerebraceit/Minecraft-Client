package dev.aurora.smoke;

import dev.aurora.core.builder.BuildSession;
import dev.aurora.minecraft.AuroraClient;
import dev.aurora.minecraft.builder.DirectSchematic;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Native pace/reach regression: build what is visible first and walk directly only when necessary. */
public final class BuildSpeedCheck {
    private static final BlockPos BASE=new BlockPos(-416,-60,-628);
    private static final BlockPos STANCE=BASE.above();
    private static final BlockPos WALK_START=BASE.offset(20,0,0),WALK_TARGET=WALK_START.offset(8,0,0);
    private static final Map<BlockPos,BlockState> expected=new LinkedHashMap<>();
    private static volatile boolean fixtureReady;
    private static int ticks,stage,phaseTick,startedTick,previousClicks,lastClickTick,stationaryTicks;
    private static Vec3 origin,previousFeet;
    private static double travelled,maxOffAxis;

    private BuildSpeedCheck() { }
    public static void start(Minecraft mc,AuroraClient client) {
        client.modules().get("auto_builder").setEnabled(false);client.builder().stop("Preparing building pace regression");mc.gui.setScreen(null);
        ticks=0;stage=0;phaseTick=0;fixtureReady=false;expected.clear();
        // An empty inner ring avoids body collisions. The pedestal leaves every support face visible.
        for(int[] offset:new int[][]{{-2,-1},{-2,0},{-2,1},{-1,-2},{0,-2},{1,-2},{2,-1},{2,0},{2,1},{-1,2},{0,2},{1,2}})
            expected.put(BASE.offset(offset[0],0,offset[1]),Blocks.STONE.defaultBlockState());
        var uuid=mc.player.getUUID();
        mc.getSingleplayerServer().execute(()->{
            var server=mc.getSingleplayerServer();var world=server.overworld();var player=server.getPlayerList().getPlayer(uuid);
            player.setGameMode(GameType.SURVIVAL);player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(4.5);
            for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++) {
                world.setBlockAndUpdate(BASE.offset(x,-1,z),Blocks.STONE.defaultBlockState());
                for(int y=0;y<=5;y++)world.setBlockAndUpdate(BASE.offset(x,y,z),Blocks.AIR.defaultBlockState());
            }
            world.setBlockAndUpdate(BASE,Blocks.STONE.defaultBlockState());
            player.getInventory().clearContent();player.getInventory().setItem(0,new ItemStack(Items.STONE,12));
            player.getInventory().setSelectedSlot(0);player.inventoryMenu.broadcastChanges();
            player.teleportTo(STANCE.getX()+0.5,STANCE.getY(),STANCE.getZ()+0.5);fixtureReady=true;
        });
    }
    public static boolean tick(Minecraft mc,AuroraClient client) {
        if(++ticks>600)throw new AssertionError("Building speed test timed out: stage="+stage+", status="+client.builder().status());
        if(stage==0) {
            if(!fixtureReady || ticks<20 || !mc.player.blockPosition().equals(STANCE) || !mc.level.getBlockState(BASE).is(Blocks.STONE)
                || client.builder().inventory().getOrDefault("minecraft:stone",0)!=12)return false;
            require(mc.gameMode.getPlayerMode()==GameType.SURVIVAL,"Pace test must run in survival");
            startDocument(mc,client,expected,"Twelve visible blocks from one stance");
            origin=mc.player.position();previousFeet=origin;startedTick=ticks;previousClicks=0;lastClickTick=-1;stage=1;return false;
        }
        if(stage==1) {
            healthy(client,"Stationary building");checkClicks(client);
            require(mc.player.position().distanceTo(origin)<=0.35,"Visible in-range blocks must be built before walking away from the current stance");
            require(ticks-startedTick<=100,"Twelve visible ordinary blocks must finish within five seconds of simulation time");
            if(client.builder().state()!=BuildSession.State.COMPLETE)return false;
            require(expected.entrySet().stream().allMatch(e->mc.level.getBlockState(e.getKey())==e.getValue()),"All twelve stationary placements must match the server-confirmed final states");
            require(client.builder().placementClicks()==12,"Stationary build should send exactly twelve placement clicks");
            require(client.builder().maxPlacementDistance()<=4.500001,"Every stationary click must use normal survival reach");
            require(client.builder().materials()!=null && client.builder().materials().remainingItems()==0,"Stationary material count must reach zero");
            stationaryTicks=ticks-startedTick;pass("Twelve visible blocks built without walking in "+stationaryTicks+" ticks, with normal reach and four-tick click spacing");
            phaseTick=ticks;stage=2;return false;
        }
        if(stage==2) {
            if(ticks-phaseTick<10)return false;
            try {Screenshot.grab(mc.gameDirectory,"aurora-building-speed.png",mc.gameRenderer.mainRenderTarget(),1,message->AuroraClient.LOG.info("Smoke screenshot: {}",message.getString()));}
            catch(Exception error){throw new AssertionError("Could not capture building-speed evidence",error);}
            client.builder().stop("Preparing direct walking and normal reach check");fixtureReady=false;phaseTick=ticks;
            var uuid=mc.player.getUUID();
            mc.getSingleplayerServer().execute(()->{
                var server=mc.getSingleplayerServer();var world=server.overworld();var player=server.getPlayerList().getPlayer(uuid);
                for(int x=-3;x<=12;x++)for(int z=-3;z<=3;z++) {
                    world.setBlockAndUpdate(WALK_START.offset(x,-1,z),Blocks.STONE.defaultBlockState());
                    for(int y=0;y<=5;y++)world.setBlockAndUpdate(WALK_START.offset(x,y,z),Blocks.AIR.defaultBlockState());
                }
                // Simulate another mod raising the reach attribute. The builder must still cap its clicks at 4.5.
                player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(8.0);
                player.getInventory().clearContent();player.getInventory().setItem(0,new ItemStack(Items.STONE));
                player.getInventory().setSelectedSlot(0);player.inventoryMenu.broadcastChanges();
                player.teleportTo(WALK_START.getX()+0.5,WALK_START.getY(),WALK_START.getZ()+0.5);fixtureReady=true;
            });
            stage=3;return false;
        }
        if(stage==3) {
            if(!fixtureReady || ticks-phaseTick<20 || !mc.player.blockPosition().equals(WALK_START) || !mc.level.hasChunkAt(WALK_TARGET)
                || !mc.level.getBlockState(WALK_TARGET.below()).is(Blocks.STONE) || mc.player.blockInteractionRange()<7.9
                || client.builder().inventory().getOrDefault("minecraft:stone",0)!=1)return false;
            startDocument(mc,client,Map.of(WALK_TARGET,Blocks.STONE.defaultBlockState()),"Direct walk with survival reach cap");
            origin=mc.player.position();previousFeet=origin;travelled=0;maxOffAxis=0;startedTick=ticks;previousClicks=0;lastClickTick=-1;stage=4;return false;
        }
        healthy(client,"Direct walking");checkClicks(client);
        Vec3 feet=mc.player.position();double step=Math.hypot(feet.x-previousFeet.x,feet.z-previousFeet.z);travelled+=step;previousFeet=feet;
        maxOffAxis=Math.max(maxOffAxis,Math.abs(feet.z-origin.z));
        require(step<=0.30,"Walking must use ordinary player movement, without teleporting or a speed boost");
        require(feet.x>=origin.x-0.25,"The builder must not walk away from a target on an open straight route");
        require(maxOffAxis<=0.65,"A straight open route must not wander sideways toward unrelated viewpoints");
        require(travelled<=5.75,"The builder must stop as soon as a normal-reach placement is available");
        require(ticks-startedTick<=140,"An open eight-block target should be reached and placed within seven seconds");
        if(client.builder().placementClicks()>0) {
            require(feet.x-origin.x>=3.0,"An eight-block target must not be clicked from its initial out-of-range stance");
            require(client.builder().maxPlacementDistance()<=4.500001,"A modded eight-block reach attribute must not extend the builder's survival reach");
        }
        if(client.builder().state()!=BuildSession.State.COMPLETE)return false;
        require(client.builder().placementClicks()==1 && mc.level.getBlockState(WALK_TARGET).is(Blocks.STONE),"Exactly one in-range click must complete the walking fixture");
        require(client.builder().inventory().isEmpty(),"The direct-walk fixture consumes only its one actual block item");
        var uuid=mc.player.getUUID();mc.getSingleplayerServer().execute(()->{
            var player=mc.getSingleplayerServer().getPlayerList().getPlayer(uuid);if(player!=null)player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(4.5);
        });
        pass("Direct walk completed in "+(ticks-startedTick)+" ticks over "+String.format(Locale.ROOT,"%.2f",travelled)+" blocks; actual click reach "+String.format(Locale.ROOT,"%.3f",client.builder().maxPlacementDistance())+", despite an eight-block attribute");
        return true;
    }
    private static void startDocument(Minecraft mc,AuroraClient client,Map<BlockPos,BlockState> world,String name) {
        var anchor=mc.player.blockPosition().immutable();var relative=new HashMap<BlockPos,BlockState>();world.forEach((pos,state)->relative.put(pos.subtract(anchor),state));
        var doc=new DirectSchematic(name,Path.of("schematics/aurora-smoke/building-speed.litematic"),relative);
        client.builder().load(doc);client.builder().startSnapshot(doc.at(anchor));
    }
    private static void healthy(AuroraClient client,String phase) {
        var state=client.builder().state();
        if(state==BuildSession.State.PAUSED || state==BuildSession.State.MISSING_MATERIALS)throw new AssertionError(phase+" stopped: "+client.builder().status());
    }
    private static void checkClicks(AuroraClient client) {
        int clicks=client.builder().placementClicks();
        if(clicks==previousClicks)return;
        require(clicks==previousClicks+1,"At most one placement action may be sent in a tick");
        if(lastClickTick>=0)require(ticks-lastClickTick>=4,"Automatic placement must respect vanilla's four-tick held-use pace");
        lastClickTick=ticks;previousClicks=clicks;
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    private static void pass(String message) {AuroraClient.LOG.info("SMOKE PASS: {}",message);}
}
