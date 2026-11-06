package dev.aurora.minecraft.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.*;

/** Uses the native water/lava atlas sprites and biome tint for the fluid preview. */
public record FluidGhost(TextureAtlasSprite still,TextureAtlasSprite flowing,int tint,float height) {
    public void submit(PoseStack pose,SubmitNodeCollector output,int opacity) {
        int color=(opacity&0xff000000)|(tint&0xffffff);
        output.submitCustomGeometry(pose,RenderTypes.entityTranslucent(still.atlasLocation(),false),(transform,vertices)->{
            face(vertices,transform,still,color,0,1,0,new float[][]{{0,height,0},{0,height,1},{1,height,1},{1,height,0}});
            face(vertices,transform,still,color,0,-1,0,new float[][]{{0,0,1},{0,0,0},{1,0,0},{1,0,1}});
        });
        output.submitCustomGeometry(pose,RenderTypes.entityTranslucent(flowing.atlasLocation(),false),(transform,vertices)->{
            face(vertices,transform,flowing,color,0,0,-1,new float[][]{{1,0,0},{0,0,0},{0,height,0},{1,height,0}});
            face(vertices,transform,flowing,color,0,0,1,new float[][]{{0,0,1},{1,0,1},{1,height,1},{0,height,1}});
            face(vertices,transform,flowing,color,-1,0,0,new float[][]{{0,0,0},{0,0,1},{0,height,1},{0,height,0}});
            face(vertices,transform,flowing,color,1,0,0,new float[][]{{1,0,1},{1,0,0},{1,height,0},{1,height,1}});
        });
    }
    private static void face(VertexConsumer vertices,PoseStack.Pose pose,TextureAtlasSprite sprite,int color,int nx,int ny,int nz,float[][] corners) {
        float[][] uv={{sprite.getU0(),sprite.getV1()},{sprite.getU1(),sprite.getV1()},{sprite.getU1(),sprite.getV0()},{sprite.getU0(),sprite.getV0()}};
        for(int i=0;i<4;i++)vertices.addVertex(pose,corners[i][0],corners[i][1],corners[i][2]).setColor(color).setUv(uv[i][0],uv[i][1])
            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(0xf000f0).setNormal(pose,nx,ny,nz);
    }
}
