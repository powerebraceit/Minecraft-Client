package dev.aurora.minecraft.builder;

import java.util.List;

/** Immutable extraction result attached to the vanilla level render state. */
public interface SchematicPreviewState {
    List<SchematicPreview.Ghost> aurora$ghosts();
    void aurora$ghosts(List<SchematicPreview.Ghost> ghosts);
}
