package dev.aurora.minecraft.builder;

import dev.aurora.core.builder.BuildPlan;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

/** Reads the public .litematic NBT format directly; no Litematica classes or libraries are used. */
public record DirectSchematic(String name, Path file, Map<BlockPos,BlockState> blocks) {
    public DirectSchematic { blocks=Map.copyOf(blocks); }
    public record Snapshot(BuildPlan plan,Map<BlockPos,BlockState> states,Map<BlockPos,List<BlockPos>> companions) {
        public Snapshot {
            states=Map.copyOf(states);
            companions=companions.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,e->List.copyOf(e.getValue())));
        }
    }
    public static DirectSchematic read(Path file) throws IOException {
        if(!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))throw new IOException("Choose a .litematic file");
        if(Files.size(file)>32*1024*1024)throw new IOException("Compressed schematic exceeds 32 MiB");
        CompoundTag root=NbtIo.readCompressed(file,NbtAccounter.create(64*1024*1024));
        int version=root.getIntOr("Version",0);
        if(version<5 || version>7)throw new IOException("Supported .litematic versions are 5, 6 and 7; found "+version);
        CompoundTag regions=root.getCompound("Regions").orElseThrow(()->new IOException("Schematic has no Regions"));
        if(regions.isEmpty())throw new IOException("Schematic has no regions");
        Map<BlockPos,BlockState> raw=new HashMap<>();Set<BlockPos> occupied=new HashSet<>();
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE;long cells=0;
        try {
            for(String regionName:regions.keySet().stream().sorted().toList()) {
                CompoundTag region=regions.getCompound(regionName).orElseThrow(()->new IOException("Invalid region: "+regionName));
                CompoundTag position=region.getCompound("Position").orElseThrow(()->new IOException("Missing region Position"));
                CompoundTag size=region.getCompound("Size").orElseThrow(()->new IOException("Missing region Size"));
                int sx=coordinate(size,"x"),sy=coordinate(size,"y"),sz=coordinate(size,"z");
                if(sx==0 || sy==0 || sz==0 || sx==Integer.MIN_VALUE || sy==Integer.MIN_VALUE || sz==Integer.MIN_VALUE)throw new IOException("Invalid region dimensions");
                int dx=Math.abs(sx),dy=Math.abs(sy),dz=Math.abs(sz);
                long volume=Math.multiplyExact(Math.multiplyExact((long)dx,dy),dz);
                cells=Math.addExact(cells,volume);
                if(cells>262144)throw new IOException("Schematic exceeds the 262144-cell limit");
                int x0=Math.addExact(coordinate(position,"x"),Math.min(0,sx+1));
                int y0=Math.addExact(coordinate(position,"y"),Math.min(0,sy+1));
                int z0=Math.addExact(coordinate(position,"z"),Math.min(0,sz+1));
                minX=Math.min(minX,x0);minY=Math.min(minY,y0);minZ=Math.min(minZ,z0);
                ListTag paletteTag=region.getList("BlockStatePalette").orElseThrow(()->new IOException("Missing block palette"));
                if(paletteTag.isEmpty() || paletteTag.size()>65536)throw new IOException("Invalid palette size");
                List<BlockState> palette=new ArrayList<>();
                for(int i=0;i<paletteTag.size();i++)palette.add(state(paletteTag.getCompound(i).orElseThrow(()->new IOException("Invalid palette entry"))));
                long[] data=region.getLongArray("BlockStates").orElseThrow(()->new IOException("Missing BlockStates"));
                int bits=Math.max(2,32-Integer.numberOfLeadingZeros(palette.size()-1));
                if(data.length!=(volume*bits+63)/64)throw new IOException("Invalid packed block array length");
                long mask=(1L<<bits)-1;
                for(int y=0;y<dy;y++)for(int z=0;z<dz;z++)for(int x=0;x<dx;x++) {
                    long offset=((long)y*dz*dx+(long)z*dx+x)*bits;
                    int word=(int)(offset/64),shift=(int)(offset%64);
                    long value=data[word]>>>shift;
                    if(shift+bits>64)value|=data[word+1]<<(64-shift);
                    int id=(int)(value&mask);
                    if(id>=palette.size())throw new IOException("Block palette index out of range");
                    BlockPos pos=new BlockPos(Math.addExact(x0,x),Math.addExact(y0,y),Math.addExact(z0,z));
                    if(!occupied.add(pos))throw new IOException("Overlapping schematic regions are not supported");
                    BlockState state=palette.get(id);if(!state.isAir())raw.put(pos,state);
                }
            }
            if(raw.isEmpty())throw new IOException("Schematic contains no non-air blocks");
            Map<BlockPos,BlockState> normalized=new HashMap<>();
            for(var e:raw.entrySet())normalized.put(new BlockPos(Math.subtractExact(e.getKey().getX(),minX),Math.subtractExact(e.getKey().getY(),minY),Math.subtractExact(e.getKey().getZ(),minZ)),e.getValue());
            return new DirectSchematic(root.getCompoundOrEmpty("Metadata").getStringOr("Name",file.getFileName().toString()),file.toAbsolutePath(),normalized);
        }catch(ArithmeticException | IllegalArgumentException error) { throw new IOException("Invalid schematic data: "+error.getMessage(),error); }
    }
    private static int coordinate(CompoundTag tag,String name) throws IOException { return tag.getInt(name).orElseThrow(()->new IOException("Missing coordinate "+name)); }
    private static BlockState state(CompoundTag tag) throws IOException {
        String name=tag.getString("Name").or(()->tag.getString("id")).orElseThrow(()->new IOException("Palette entry has no block name"));
        Block block=BuiltInRegistries.BLOCK.getOptional(Identifier.parse(name)).orElseThrow(()->new IOException("Unknown block in this Minecraft version: "+name));
        BlockState state=block.defaultBlockState();
        CompoundTag props=tag.getCompound("Properties").orElseGet(()->tag.getCompoundOrEmpty("properties"));
        for(String key:props.keySet()) {
            Property<?> property=block.getStateDefinition().getProperty(key);
            if(property==null)throw new IOException("Unknown property "+key+" for "+name);
            state=set(state,property,props.getString(key).orElseThrow(()->new IOException("Invalid block property")));
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState set(BlockState state,Property<T> property,String value) throws IOException {
        return state.setValue(property,property.getValue(value).orElseThrow(()->new IOException("Invalid "+property.getName()+" value: "+value)));
    }
    public Snapshot at(BlockPos anchor) {
        Map<BlockPos,BlockState> world=new HashMap<>();
        for(var e:blocks.entrySet())world.put(new BlockPos(Math.addExact(anchor.getX(),e.getKey().getX()),Math.addExact(anchor.getY(),e.getKey().getY()),Math.addExact(anchor.getZ(),e.getKey().getZ())),e.getValue());
        Map<BlockPos,List<BlockPos>> companions=new HashMap<>();List<BuildPlan.Block> plan=new ArrayList<>();
        for(var e:world.entrySet()) {
            BlockPos pos=e.getKey();BlockState state=e.getValue();BlockPos parent=null;
            if((state.getBlock() instanceof DoorBlock || state.getBlock() instanceof DoublePlantBlock) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==DoubleBlockHalf.UPPER)parent=pos.below();
            if(state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART)==BedPart.HEAD)parent=pos.relative(state.getValue(BedBlock.FACING).getOpposite());
            if(parent!=null) {
                if(!world.containsKey(parent) || world.get(parent).getBlock()!=state.getBlock())throw new IllegalStateException("Missing lower/foot part at "+pos.toShortString());
                companions.computeIfAbsent(parent,p->new ArrayList<>()).add(pos);continue;
            }
            var item=state.getBlock().asItem();
            if(!(item instanceof BlockItem))throw new IllegalStateException("No supported block item for "+state);
            int count=state.getBlock() instanceof SlabBlock && state.getValue(SlabBlock.TYPE)==SlabType.DOUBLE?2:1;
            plan.add(new BuildPlan.Block(pos.getX(),pos.getY(),pos.getZ(),BuiltInRegistries.ITEM.getKey(item).toString(),count,state.toString()));
        }
        return new Snapshot(new BuildPlan(plan),world,companions);
    }
}
