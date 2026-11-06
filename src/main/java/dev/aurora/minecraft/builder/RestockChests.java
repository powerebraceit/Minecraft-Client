package dev.aurora.minecraft.builder;

import dev.aurora.minecraft.AuroraClient;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class RestockChests {
    public record Chest(String dimension,BlockPos pos) { }
    private final AuroraClient client;
    public RestockChests(AuroraClient client) { this.client=client; }
    public String dimension() {
        Minecraft mc=Minecraft.getInstance();
        String server=mc.getCurrentServer()!=null ? mc.getCurrentServer().ip : mc.getSingleplayerServer()!=null
            ? mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().toString() : "unknown";
        String scope=Base64.getUrlEncoder().withoutPadding().encodeToString(server.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return scope+"@"+mc.level.dimension().identifier();
    }
    public List<Chest> all() {
        List<Chest> result=new ArrayList<>();
        for(String entry:client.config().option("auto_builder","chests","").split(";")) {
            try {
                String[] v=entry.split(",");if(v.length!=4)continue;
                Chest chest=new Chest(v[0],new BlockPos(Integer.parseInt(v[1]),Integer.parseInt(v[2]),Integer.parseInt(v[3])));
                if(!result.contains(chest) && result.size()<32)result.add(chest);
            }catch(IllegalArgumentException ignored) { }
        }
        return List.copyOf(result);
    }
    public List<Chest> current() { return all().stream().filter(c->c.dimension.equals(dimension())).toList(); }
    public Chest canonical(BlockPos pos) {
        Minecraft mc=Minecraft.getInstance();
        var state=mc.level.getBlockState(pos);
        if(!(state.getBlock() instanceof ChestBlock))throw new IllegalStateException("Look at a chest to register it");
        if(state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE) {
            BlockPos other=pos.relative(ChestBlock.getConnectedDirection(state));
            if(other.asLong()<pos.asLong())pos=other;
        }
        return new Chest(dimension(),pos.immutable());
    }
    public void bindLookedAt() {
        Minecraft mc=Minecraft.getInstance();
        if(!(mc.hitResult instanceof BlockHitResult hit) || hit.getType()!=HitResult.Type.BLOCK)throw new IllegalStateException("Look at a chest first");
        Chest chest=canonical(hit.getBlockPos());
        List<Chest> chests=new ArrayList<>(all());
        if(!chests.remove(chest)) {
            if(chests.size()>=32)throw new IllegalStateException("At most 32 restock chests are supported");
            chests.add(chest);
        }
        save(chests);
    }
    public void save(List<Chest> chests) {
        String value=String.join(";",chests.stream().map(c->c.dimension+","+c.pos.getX()+","+c.pos.getY()+","+c.pos.getZ()).toList());
        client.config().setting("auto_builder","chests",value);client.save();
    }
    public void outlines() {
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null || mc.player==null)return;
        int color=client.config().number("auto_builder","chest_color",0xff9b8aff);
        try(var ignored=mc.collectPerTickGizmos()) {
            for(Chest chest:current()) {
                if(mc.level.hasChunkAt(chest.pos) && mc.player.distanceToSqr(chest.pos.getX()+0.5,chest.pos.getY()+0.5,chest.pos.getZ()+0.5)<96*96) {
                    var state=mc.level.getBlockState(chest.pos);
                    if(!(state.getBlock() instanceof ChestBlock))continue;
                    Gizmos.cuboid(chest.pos,GizmoStyle.stroke(color,2)).setAlwaysOnTop();
                    if(state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE)
                        Gizmos.cuboid(chest.pos.relative(ChestBlock.getConnectedDirection(state)),GizmoStyle.stroke(color,2)).setAlwaysOnTop();
                }
            }
        }
    }
}
