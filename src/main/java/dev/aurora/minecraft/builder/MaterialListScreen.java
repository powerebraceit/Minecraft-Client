package dev.aurora.minecraft.builder;

import dev.aurora.minecraft.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** Complete, scrolling inventory checklist, including materials beyond the compact HUD. */
public final class MaterialListScreen extends Screen {
    private final Screen parent;
    private final AuroraClient client;
    private int scroll;
    private final String notice;
    public MaterialListScreen(Screen parent,AuroraClient client) {this(parent,client,"");}
    public MaterialListScreen(Screen parent,AuroraClient client,String notice) {super(Component.literal("Schematic materials"));this.parent=parent;this.client=client;this.notice=notice;}
    public static ItemStack stack(String item) {return item.isEmpty()?ItemStack.EMPTY:new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(item)));}
    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Preview in world"),b->minecraft.gui.setScreen(null)).bounds(12,height-28,110,20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"),b->onClose()).bounds(width-67,height-28,55,20).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partialTick) {
        var builder=client.builder();var canvas=new MinecraftCanvas(graphics,font);
        canvas.roundedRect(0,0,width,height,0,0xee111522);
        canvas.text("SCHEMATIC MATERIALS",12,12,14,0xffedf1ff);
        canvas.text(font.plainSubstrByWidth(builder.document()==null?builder.schematicName():builder.document().file().getFileName().toString(),width-24),12,34,9,0xffb5accf);
        if(!builder.documentInWorld() || builder.materials()==null) {
            canvas.text(font.plainSubstrByWidth(builder.documentInWorld()?builder.materialsError():"Select a file and anchor it in this world first",width-24),12,66,9,0xffffce7a);
        }else {
            var rows=builder.materials().rows();var inventory=RestockJob.inventory();
            int nameWidth=Math.max(40,width-220),start=width-202;
            canvas.text("Block / item",12,57,9,0xffb5accf);
            for(int i=0;i<4;i++)canvas.text(new String[]{"Total","Left","Have","Missing"}[i],start+i*49,57,9,0xffb5accf);
            int visible=Math.max(1,(height-122)/24);scroll=Math.clamp(scroll,0,Math.max(0,rows.size()-visible));
            for(int i=scroll;i<Math.min(rows.size(),scroll+visible);i++) {
                var row=rows.get(i);int y=73+(i-scroll)*24,have=inventory.getOrDefault(row.item(),0),missing=Math.max(0,row.remaining()-have);
                canvas.roundedRect(12,y,width-24,22,4,i%2==0?0xff252b40:0xff1c2235);
                ItemStack stack=stack(row.item());graphics.item(stack,16,y+3);
                canvas.text(font.plainSubstrByWidth((stack.isEmpty()?row.name():stack.getHoverName().getString())+(row.manual()?" *":""),nameWidth-29),37,y+7,9,0xffedf1ff);
                int[] counts={row.total(),row.remaining(),have,missing};
                for(int col=0;col<4;col++)canvas.text(Integer.toString(counts[col]),start+col*49,y+7,9,col==3 && missing>0?0xffffce7a:row.remaining()==0?0xff8cdea4:0xffedf1ff);
            }
            String footer=!notice.isBlank()?notice:builder.materials().hasManualBlocks()?"* Manual block count; automatic placement is unavailable. Other materials are still listed.":builder.materials().rows().stream().anyMatch(r->r.item().equals("minecraft:water_bucket") || r.item().equals("minecraft:lava_bucket"))?"Water: max 2 seed buckets + refill pool. Lava: one bucket per source.":"Left: "+builder.materials().remainingItems()+" | Have: main inventory | Scroll for more";
            canvas.text(font.plainSubstrByWidth(footer,width-24),12,height-42,8,0xffb5accf);
        }
        super.extractRenderState(graphics,mouseX,mouseY,partialTick);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {scroll-=(int)(vertical*2);return true;}
    @Override public void onClose() {minecraft.gui.setScreen(parent);}
    @Override public boolean isPauseScreen() {return false;}
}
