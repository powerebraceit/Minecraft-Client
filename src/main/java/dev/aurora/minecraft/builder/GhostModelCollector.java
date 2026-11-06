package dev.aurora.minecraft.builder;

import dev.aurora.minecraft.mixin.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.*;

/** Keeps native special block models (for example chests) translucent too. */
final class GhostModelCollector implements SubmitNodeCollector {
    private final OrderedSubmitNodeCollector output;
    GhostModelCollector(OrderedSubmitNodeCollector output) {this.output=output;}
    @Override public OrderedSubmitNodeCollector order(int order) {
        return new GhostModelCollector(output instanceof SubmitNodeCollector c?c.order(order):output);
    }
    private static RenderType translucent(RenderType type) {
        var setup=((GhostRenderTypeAccessor)(Object)type).aurora$state();
        Object binding=((GhostRenderSetupAccessor)(Object)setup).aurora$textures().get("Sampler0");
        return binding==null?type:RenderTypes.entityTranslucent(((GhostTextureAccessor)binding).aurora$location(),false);
    }
    @Override public void submitShadow(com.mojang.blaze3d.vertex.PoseStack p0, float p1, java.util.List<net.minecraft.client.renderer.entity.state.EntityRenderState.ShadowPiece> p2) { output.submitShadow(p0, p1, p2); }
    @Override public void submitNameTag(com.mojang.blaze3d.vertex.PoseStack p0, net.minecraft.world.phys.Vec3 p1, int p2, net.minecraft.network.chat.Component p3, boolean p4, int p5, net.minecraft.client.renderer.state.level.CameraRenderState p6) { output.submitNameTag(p0, p1, p2, p3, p4, p5, p6); }
    @Override public void submitText(com.mojang.blaze3d.vertex.PoseStack p0, float p1, float p2, net.minecraft.util.FormattedCharSequence p3, boolean p4, net.minecraft.client.gui.Font.DisplayMode p5, int p6, int p7, int p8, int p9) { output.submitText(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9); }
    @Override public void submitTextBackground(com.mojang.blaze3d.vertex.PoseStack p0, float p1, float p2, float p3, float p4, int p5, net.minecraft.client.gui.Font.DisplayMode p6, int p7) { output.submitTextBackground(p0, p1, p2, p3, p4, p5, p6, p7); }
    @Override public void submitFlame(com.mojang.blaze3d.vertex.PoseStack p0, net.minecraft.client.renderer.entity.state.EntityRenderState p1, org.joml.Quaternionf p2) { output.submitFlame(p0, p1, p2); }
    @Override public void submitLeash(com.mojang.blaze3d.vertex.PoseStack p0, net.minecraft.client.renderer.entity.state.EntityRenderState.LeashState p1) { output.submitLeash(p0, p1); }
    @Override public <S> void submitModel(net.minecraft.client.model.Model<? super S> p0, S p1, com.mojang.blaze3d.vertex.PoseStack p2, net.minecraft.client.renderer.rendertype.RenderType p3, int p4, int p5, int p6, net.minecraft.client.renderer.texture.UvMapping p7, int p8) { output.submitModel(p0, p1, p2, translucent(p3), p4, p5, (p6 & 0xffffff) | 0x88000000, p7, p8); }
    @Override public <S> void submitCrumblingOverlay(net.minecraft.client.model.Model<? super S> p0, S p1, com.mojang.blaze3d.vertex.PoseStack p2, net.minecraft.client.renderer.rendertype.RenderType p3, int p4, int p5, int p6, net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay p7) { output.submitCrumblingOverlay(p0, p1, p2, p3, p4, p5, p6, p7); }
    @Override public void submitMovingBlock(com.mojang.blaze3d.vertex.PoseStack p0, net.minecraft.client.renderer.block.MovingBlockRenderState p1, int p2) { output.submitMovingBlock(p0, p1, p2); }
    @Override public void submitBlockModel(com.mojang.blaze3d.vertex.PoseStack p0, net.minecraft.client.renderer.rendertype.RenderType p1, java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> p2, int[] p3, int p4, int p5, int p6) { ((SubmitNodeCollection)output).translucentBlocksAndItems.submit(new BlockModelFeatureRenderer.Submit(p0.last().copy(),translucent(p1),java.util.List.copyOf(p2),p3.clone(),p4,p5,SchematicPreview.GHOST_COLOR,null)); }
    @Override public void submitBreakingBlockModel(com.mojang.blaze3d.vertex.PoseStack p0, java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> p1, int p2, boolean p3) { output.submitBreakingBlockModel(p0, p1, p2, p3); }
    @Override public void submitShapeOutline(com.mojang.blaze3d.vertex.PoseStack p0, net.minecraft.world.phys.shapes.VoxelShape p1, net.minecraft.client.renderer.rendertype.RenderType p2, int p3, float p4, boolean p5) { output.submitShapeOutline(p0, p1, p2, p3, p4, p5); }
    @Override public void submitItem(com.mojang.blaze3d.vertex.PoseStack p0, net.minecraft.world.item.ItemDisplayContext p1, int p2, int p3, int p4, int[] p5, net.minecraft.client.resources.model.geometry.ItemQuads p6, net.minecraft.client.renderer.item.ItemStackRenderState.FoilType p7) { output.submitItem(p0, p1, p2, p3, p4, p5, p6, p7); }
    @Override public void submitCustomGeometry(com.mojang.blaze3d.vertex.PoseStack p0, net.minecraft.client.renderer.rendertype.RenderType p1, net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer p2) { output.submitCustomGeometry(p0, p1, p2); }
    @Override public void submitQuadParticleGroup(net.minecraft.client.renderer.state.level.QuadParticleRenderState p0) { output.submitQuadParticleGroup(p0); }
    @Override public void submitGizmoPrimitives(net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives.Group p0, net.minecraft.client.renderer.state.level.CameraRenderState p1, boolean p2) { output.submitGizmoPrimitives(p0, p1, p2); }
}
