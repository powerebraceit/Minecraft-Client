package dev.aurora;

import dev.aurora.minecraft.builder.DirectSchematic;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;

public final class DirectSchematicJUnitTest {
    @TempDir Path directory;
    @BeforeAll static void initializeRegistry() {SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    private static CompoundTag pos(int x,int y,int z) {var p=new CompoundTag();p.putInt("x",x);p.putInt("y",y);p.putInt("z",z);return p;}
    private static CompoundTag region(CompoundTag position,CompoundTag size,List<BlockState> states,long...data) {
        var r=new CompoundTag();r.put("Position",position);r.put("Size",size);
        var palette=new ListTag();for(var state:states)palette.add(NbtUtils.writeBlockState(state));
        r.put("BlockStatePalette",palette);r.putLongArray("BlockStates",data);return r;
    }
    private Path write(int version,CompoundTag regions) throws Exception {
        var root=new CompoundTag();root.putInt("Version",version);root.put("Regions",regions);
        var meta=new CompoundTag();meta.putString("Name","A real local .litematic");root.put("Metadata",meta);
        Path file=directory.resolve("schema with spaces.litematic");NbtIo.writeCompressed(root,file);return file;
    }
    @Test void readsSignedDimensionsAndReanchorsMultipleLayers() throws Exception {
        var regions=new CompoundTag();regions.put("negative",region(pos(5,0,7),pos(-2,2,-2),List.of(Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState()),1L|(1L<<14)));
        var doc=DirectSchematic.read(write(7,regions));
        assertEquals(Set.of(new BlockPos(0,0,0),new BlockPos(1,1,1)),doc.blocks().keySet());
        assertEquals(2,doc.at(new BlockPos(10,4,20)).plan().blocks().size());
        assertTrue(doc.at(new BlockPos(10,4,20)).states().containsKey(new BlockPos(11,5,21)));
    }
    @Test void decodesContinuousPaletteValuesAcrossLongBoundaries() throws Exception {
        var palette=List.of(Blocks.AIR,Blocks.STONE,Blocks.DIRT,Blocks.COBBLESTONE,Blocks.OAK_PLANKS,Blocks.BRICKS,Blocks.SAND,Blocks.GLASS,Blocks.GRAVEL,Blocks.COAL_BLOCK,Blocks.IRON_BLOCK,Blocks.GOLD_BLOCK,Blocks.DIAMOND_BLOCK,Blocks.EMERALD_BLOCK,Blocks.LAPIS_BLOCK,Blocks.REDSTONE_BLOCK,Blocks.OBSIDIAN,Blocks.NETHERRACK).stream().map(b->b.defaultBlockState()).toList();
        var regions=new CompoundTag();regions.put("cross-word",region(pos(0,0,0),pos(13,1,1),palette,1L<<60,1L));
        var doc=DirectSchematic.read(write(6,regions));
        assertEquals(Map.of(new BlockPos(12,0,0),Blocks.NETHERRACK.defaultBlockState()),doc.blocks());
    }
    @Test void normalizesSeparateRegionsUsingTheirFullSelectionBounds() throws Exception {
        var regions=new CompoundTag();regions.put("a",region(pos(3,0,5),pos(1,1,1),List.of(Blocks.STONE.defaultBlockState()),0));
        regions.put("b",region(pos(-2,2,4),pos(1,1,1),List.of(Blocks.DIRT.defaultBlockState()),0));
        var doc=DirectSchematic.read(write(5,regions));
        assertTrue(doc.blocks().containsKey(new BlockPos(5,0,1)));
        assertTrue(doc.blocks().containsKey(new BlockPos(0,2,0)));
    }
    @Test void rejectsOverlapsBadLengthsUnknownBlocksAndFutureVersions() throws Exception {
        var r=region(pos(0,0,0),pos(1,1,1),List.of(Blocks.STONE.defaultBlockState()),0);
        var regions=new CompoundTag();regions.put("a",r);regions.put("b",r.copy());
        assertThrows(java.io.IOException.class,()->DirectSchematic.read(write(7,regions)));
        regions.remove("b");r.putLongArray("BlockStates",new long[0]);
        assertThrows(java.io.IOException.class,()->DirectSchematic.read(write(7,regions)));
        r.putLongArray("BlockStates",new long[]{0});var bad=new CompoundTag();bad.putString("Name","missing:unknown_block");var palette=new ListTag();palette.add(bad);r.put("BlockStatePalette",palette);
        assertThrows(java.io.IOException.class,()->DirectSchematic.read(write(7,regions)));
        assertThrows(java.io.IOException.class,()->DirectSchematic.read(write(8,regions)));
    }
    @Test void acceptsLegacyNameAndPropertiesPalette() throws Exception {
        var region=region(pos(0,0,0),pos(1,1,1),List.of(Blocks.STONE.defaultBlockState()),0);
        var entry=new CompoundTag();entry.putString("Name","minecraft:oak_stairs");
        var properties=new CompoundTag();properties.putString("facing","east");entry.put("Properties",properties);
        var palette=new ListTag();palette.add(entry);region.put("BlockStatePalette",palette);
        var regions=new CompoundTag();regions.put("legacy",region);
        var doc=DirectSchematic.read(write(5,regions));
        assertEquals(net.minecraft.core.Direction.EAST,doc.blocks().get(BlockPos.ZERO).getValue(net.minecraft.world.level.block.StairBlock.FACING));
    }
    @Test void countsDoubleSlabsAndDoorPairsAsActualInventoryItems() {
        var lower=Blocks.OAK_DOOR.defaultBlockState();var upper=lower.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER);
        var slab=Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.DOUBLE);
        var doc=new DirectSchematic("materials",directory.resolve("a.litematic"),Map.of(BlockPos.ZERO,lower,new BlockPos(0,1,0),upper,new BlockPos(1,0,0),slab));
        var snapshot=doc.at(new BlockPos(10,4,10));
        assertEquals(Map.of("minecraft:oak_door",1,"minecraft:stone_slab",2),dev.aurora.core.builder.BuildPlan.materials(snapshot.plan().blocks()));
        assertEquals(List.of(new BlockPos(10,5,10)),snapshot.companions().get(new BlockPos(10,4,10)));
    }
}
