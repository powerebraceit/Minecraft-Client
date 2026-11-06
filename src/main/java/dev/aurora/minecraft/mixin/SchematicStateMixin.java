package dev.aurora.minecraft.mixin;

import dev.aurora.minecraft.builder.*;
import java.util.List;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(LevelRenderState.class)
public abstract class SchematicStateMixin implements SchematicPreviewState {
    @Unique private List<SchematicPreview.Ghost> aurora$ghosts=List.of();
    public List<SchematicPreview.Ghost> aurora$ghosts() {return aurora$ghosts;}
    public void aurora$ghosts(List<SchematicPreview.Ghost> ghosts) {aurora$ghosts=ghosts;}
}
