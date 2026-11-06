package dev.aurora.minecraft.builder;

import dev.aurora.core.builder.BuildPlan;
import java.lang.reflect.*;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Optional integration: missing/incompatible Litematica never prevents Aurora itself from loading. */
public final class LitematicaBridge {
    public record Snapshot(BuildPlan plan,Map<BlockPos,BlockState> states,Object placement,String signature) {
        public Snapshot { states=Map.copyOf(states); }
    }
    public boolean available() {
        return FabricLoader.getInstance().isModLoaded("litematica") && FabricLoader.getInstance().isModLoaded("malilib");
    }
    public String name() {
        if(!available())return "Install Litematica 26.3 + MaLiLib";
        try { return (String)invoke(selected(),"getName"); }catch(RuntimeException e) { return e.getMessage(); }
    }
    private Object manager() { return invokeStatic("fi.dy.masa.litematica.data.DataManager","getSchematicPlacementManager"); }
    public Object selected() {
        if(!available())throw new IllegalStateException("Install Litematica for 26.3 and compatible MaLiLib");
        Object selected=invoke(manager(),"getSelectedSchematicPlacement");
        if(selected==null)throw new IllegalStateException("Select a schematic placement in Litematica first");
        if(!((Boolean)invoke(selected,"isEnabled")))throw new IllegalStateException("Enable the selected Litematica placement");
        return selected;
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private List<AABB> boxes(Object placement) {
        try {
            Class<? extends Enum> requirement=(Class<? extends Enum>)Class.forName("fi.dy.masa.litematica.schematic.placement.SubRegionPlacement$RequiredEnabled");
            Object enabled=Enum.valueOf(requirement,"PLACEMENT_ENABLED");
            Map<?,?> regions=(Map<?,?>)placement.getClass().getMethod("getSubRegionBoxes",requirement).invoke(placement,enabled);
            List<AABB> boxes=new ArrayList<>();
            for(Object box:regions.values())boxes.add(AABB.encapsulatingFullBlocks((BlockPos)invoke(box,"getPos1"),(BlockPos)invoke(box,"getPos2")));
            return List.copyOf(boxes);
        }catch(ReflectiveOperationException e) { throw incompatible(e); }
    }
    public String signature(Object placement) {
        return invoke(placement,"getOrigin")+":"+invoke(placement,"getRotation")+":"+invoke(placement,"getMirror")+":"+boxes(placement);
    }
    public boolean unchanged(Snapshot snapshot) {
        try { return selected()==snapshot.placement && signature(snapshot.placement).equals(snapshot.signature); }
        catch(RuntimeException e) { return false; }
    }
    public void preview(boolean enabled) {
        Object placement=selected();
        invoke(placement,"setRenderSchematic",new Class<?>[]{boolean.class},enabled);
        if(enabled) {
            try {
                Class<?> visuals=Class.forName("fi.dy.masa.litematica.config.Configs$Visuals");
                for(String field:new String[]{"ENABLE_RENDERING","ENABLE_SCHEMATIC_RENDERING"})
                    invoke(visuals.getField(field).get(null),"setBooleanValue",new Class<?>[]{boolean.class},true);
            }catch(ReflectiveOperationException e) { throw incompatible(e); }
        }
    }
    public void cornerAtFeet() {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null)throw new IllegalStateException("Join a world first");
        Object placement=selected();
        if((Boolean)invoke(placement,"isLocked"))throw new IllegalStateException("Unlock the Litematica placement first");
        var bounds=boxes(placement);
        if(bounds.isEmpty())throw new IllegalStateException("Enable at least one schematic region first");
        BlockPos minimum=new BlockPos((int)bounds.stream().mapToDouble(b->b.minX).min().orElseThrow(),
            (int)bounds.stream().mapToDouble(b->b.minY).min().orElseThrow(),(int)bounds.stream().mapToDouble(b->b.minZ).min().orElseThrow());
        BlockPos origin=(BlockPos)invoke(placement,"getOrigin");
        BlockPos desired=origin.offset(mc.player.blockPosition().subtract(minimum));
        try {
            Class<?> coordinates=Class.forName("fi.dy.masa.malilib.util.position.PositionUtils$CoordinateType");
            for(Object axis:coordinates.getEnumConstants()) {
                if((Boolean)invoke(placement,"isCoordinateLocked",new Class<?>[]{coordinates},axis))
                    throw new IllegalStateException("Unlock placement coordinates before moving its corner");
            }
            Class<?> consumer=Class.forName("fi.dy.masa.malilib.util.interfaces.IStringConsumer");
            Object feedback=Proxy.newProxyInstance(consumer.getClassLoader(),new Class<?>[]{consumer},(proxy,method,args)->null);
            invoke(placement,"setOrigin",new Class<?>[]{BlockPos.class,consumer},desired,feedback);
            if(!desired.equals(invoke(placement,"getOrigin")))throw new IllegalStateException("Placement coordinates are locked");
        }catch(ClassNotFoundException e) { throw incompatible(e); }
    }
    public Snapshot snapshot() {
        Object placement=selected();List<AABB> bounds=boxes(placement);
        if(bounds.isEmpty())throw new IllegalStateException("Enable at least one schematic region first");
        // The schematic world combines placements; reject ambiguous overlap instead of building another placement's blocks.
        for(Object other:(List<?>)invoke(manager(),"getAllSchematicsPlacements")) {
            if(other==placement || !((Boolean)invoke(other,"isEnabled")))continue;
            for(AABB a:bounds)for(AABB b:boxes(other))if(a.intersects(b))throw new IllegalStateException("Disable other overlapping schematic placements first");
        }
        double volume=bounds.stream().mapToDouble(b->b.getXsize()*b.getYsize()*b.getZsize()).sum();
        if(volume>262144)throw new IllegalStateException("Selected schematic regions exceed the 262144-cell limit");
        for(int i=0;i<bounds.size();i++)for(int j=i+1;j<bounds.size();j++)if(bounds.get(i).intersects(bounds.get(j)))
            throw new IllegalStateException("Overlapping schematic regions are not supported");
        Object schematicWorld=invokeStatic("fi.dy.masa.litematica.world.SchematicWorldHandler","getSchematicWorld");
        if(!(schematicWorld instanceof Level world))throw new IllegalStateException("Litematica preview is still loading");
        Object cache=invokeStatic("fi.dy.masa.litematica.materials.MaterialCache","getInstance");
        Map<BlockPos,BlockState> states=new LinkedHashMap<>();List<BuildPlan.Block> plan=new ArrayList<>();
        for(AABB box:bounds)for(int y=(int)box.minY;y<box.maxY;y++)for(int z=(int)box.minZ;z<box.maxZ;z++)for(int x=(int)box.minX;x<box.maxX;x++) {
            BlockPos pos=new BlockPos(x,y,z);
            if(!world.hasChunkAt(pos))throw new IllegalStateException("Wait for schematic chunks to finish loading");
            BlockState state=world.getBlockState(pos);if(state.isAir())continue;
            ItemStack stack=(ItemStack)invoke(cache,"getRequiredBuildItemForState",new Class<?>[]{BlockState.class,Level.class,BlockPos.class},state,world,pos);
            if(stack.isEmpty())throw new IllegalStateException("No placeable item for "+state);
            states.put(pos,state);plan.add(new BuildPlan.Block(x,y,z,RestockJob.itemId(stack),stack.getCount(),state.toString()));
        }
        if(plan.isEmpty())throw new IllegalStateException("No schematic blocks found; wait for Litematica to rebuild its preview");
        return new Snapshot(new BuildPlan(plan),states,placement,signature(placement));
    }
    public void easyPlace(BlockPos target) {
        Object handler;
        Class<?> providerType;
        try {
            handler=Class.forName("fi.dy.masa.malilib.registry.Registry").getField("BLOCK_PLACEMENT_POSITION_HANDLER").get(null);
            providerType=Class.forName("fi.dy.masa.malilib.interoperation.IBlockPlacementPositionProvider");
        }catch(ReflectiveOperationException error) { throw incompatible(error); }
        if(invoke(handler,"getCurrentPlacementPosition")!=null)throw new IllegalStateException("Another mod is overriding block placement; stop it first");
        Object provider=Proxy.newProxyInstance(providerType.getClassLoader(),new Class<?>[]{providerType},(proxy,method,args)->switch(method.getName()) {
            case "getPlacementPosition" -> target;
            case "equals" -> proxy==args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "Aurora placement target";
            default -> throw new UnsupportedOperationException(method.getName());
        });
        invoke(handler,"registerPositionProvider",new Class<?>[]{providerType},provider);
        try {
            if(!target.equals(invoke(handler,"getCurrentPlacementPosition")))throw new IllegalStateException("Litematica target override was rejected");
            invokeStatic("fi.dy.masa.litematica.util.EasyPlaceUtils","handleEasyPlaceWithMessage");
        }finally { invoke(handler,"unregisterPositionProvider",new Class<?>[]{providerType},provider); }
    }
    private static Object invokeStatic(String type,String method) {
        try { return Class.forName(type).getMethod(method).invoke(null); }
        catch(ReflectiveOperationException e) { throw incompatible(e); }
    }
    private static Object invoke(Object target,String method) { return invoke(target,method,new Class<?>[0]); }
    private static Object invoke(Object target,String method,Class<?>[] types,Object...args) {
        try { return target.getClass().getMethod(method,types).invoke(target,args); }
        catch(ReflectiveOperationException e) { throw incompatible(e); }
    }
    private static IllegalStateException incompatible(ReflectiveOperationException error) {
        Throwable cause=error instanceof InvocationTargetException ? error.getCause():error;
        return new IllegalStateException("Litematica operation failed: "+cause.getMessage(),cause);
    }
}
