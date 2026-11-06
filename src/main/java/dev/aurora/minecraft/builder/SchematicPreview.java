package dev.aurora.minecraft.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.*;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

/** Extract on the game thread, submit native textured models on the render thread. */
public final class SchematicPreview {
    public static final int MISSING_COLOR=0xffff4d61, WRONG_COLOR=0xffffd447, GHOST_COLOR=0xb3ffffff;
    public enum Match { CORRECT, MISSING, WRONG }
    public static Match match(BlockState expected,BlockState actual) {
        return expected==actual?Match.CORRECT:actual.isAir()?Match.MISSING:Match.WRONG;
    }
    public record Expected(BlockPos pos,BlockState state) { }
    public record Ghost(BlockPos pos,Vec3 offset,BlockModelRenderState model,int outlineColor,int opacityColor) { }
    private final BuilderRuntime builder;
    private final Map<ChunkPos,List<Expected>> chunks=new HashMap<>();
    private List<Ghost> lastExtracted=List.of();
    public SchematicPreview(BuilderRuntime builder) {this.builder=builder;}
    public List<Ghost> ghosts() {return lastExtracted;}
    public void reset(DirectSchematic document,BlockPos anchor) {
        chunks.clear();lastExtracted=List.of();
        document.blocks().forEach((relative,state)->{
            BlockPos pos=anchor.offset(relative);chunks.computeIfAbsent(new ChunkPos(pos.getX()>>4,pos.getZ()>>4),p->new ArrayList<>()).add(new Expected(pos,state));
        });
    }
    public void extract(LevelRenderState renderState) {
        var target=(SchematicPreviewState)renderState;
        Minecraft mc=Minecraft.getInstance();
        if(!builder.previewVisible() || mc.player==null) {target.aurora$ghosts(List.of());lastExtracted=List.of();return;}
        Vec3 camera=renderState.cameraRenderState.pos;
        int cx=SectionPos.blockToSectionCoord((int)Math.floor(camera.x)),cz=SectionPos.blockToSectionCoord((int)Math.floor(camera.z));
        List<Expected> candidates=new ArrayList<>();
        for(int x=cx-3;x<=cx+3;x++)for(int z=cz-3;z<=cz+3;z++) {
            for(var block:chunks.getOrDefault(new ChunkPos(x,z),List.of())) {
                if(Vec3.atCenterOf(block.pos()).distanceToSqr(camera)>48*48 || !mc.level.hasChunkAt(block.pos()))continue;
                if(match(block.state(),mc.level.getBlockState(block.pos()))!=Match.CORRECT)candidates.add(block);
            }
        }
        candidates.sort(Comparator.comparingDouble(e->Vec3.atCenterOf(e.pos()).distanceToSqr(camera)));
        List<Ghost> ghosts=new ArrayList<>();
        var models=mc.getModelManager().getBlockModelSet();
        for(var block:candidates) {
            if(ghosts.size()>=4096)break;
            BlockState expected=block.state();BlockPos pos=block.pos();
            // The complete block model set includes native special models such as chests.
            var model=new BlockModelRenderState();
            models.get(expected).update(model,expected,BlockDisplayContext.create(),expected.getSeed(pos));
            var tints=model.tintLayers();tints.clear();
            for(var tint:mc.getBlockColors().getTintSources(expected))tints.add(0xff000000 | tint.colorInWorld(expected,mc.level,pos));
            int color=match(expected,mc.level.getBlockState(pos))==Match.MISSING?MISSING_COLOR:WRONG_COLOR;
            ghosts.add(new Ghost(pos,expected.getOffset(pos),model,color,(Math.clamp(builder.previewOpacity(),10,90)*255/100<<24)|0xffffff));
        }
        lastExtracted=List.copyOf(ghosts);target.aurora$ghosts(lastExtracted);
    }
    public static void submit(LevelRenderState state,SubmitNodeCollector output) {
        var ghosts=((SchematicPreviewState)state).aurora$ghosts();
        if(ghosts.isEmpty())return;
        var pose=new PoseStack();Vec3 camera=state.cameraRenderState.pos;
        var specialOutput=new GhostModelCollector(output.order(0),ghosts.getFirst().opacityColor());
        for(var ghost:ghosts) {
            pose.pushPose();pose.translate(ghost.pos().getX()-camera.x,ghost.pos().getY()-camera.y,ghost.pos().getZ()-camera.z);
            pose.pushPose();pose.translate(ghost.offset());
            // Slight expansion keeps the intended model visible at a conflicting block's outer faces.
            pose.translate(-0.001,-0.001,-0.001);pose.scale(1.002f,1.002f,1.002f);
            ghost.model().submit(pose,specialOutput,0xf000f0,OverlayTexture.NO_OVERLAY,0);
            pose.popPose();
            pose.translate(-0.003,-0.003,-0.003);pose.scale(1.006f,1.006f,1.006f);
            output.submitShapeOutline(pose,Shapes.block(),RenderTypes.linesTranslucentNoDepthWrite(),ghost.outlineColor(),1.5f,false);
            pose.popPose();
        }
    }
}
