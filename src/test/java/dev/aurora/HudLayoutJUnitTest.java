package dev.aurora;

import dev.aurora.core.ClientConfig;
import dev.aurora.core.HudLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public final class HudLayoutJUnitTest {
    @TempDir Path directory;

    @Test void defaultArmorSitsAboveHotbarAtBottomRight() {
        var bounds = HudLayout.place(null, 100, 104, 800, 450, 692, 302);
        assertEquals(692, bounds.x());
        assertEquals(302, bounds.y());
        assertTrue(bounds.contains(700, 310));
        assertFalse(bounds.contains(792, 310));
    }

    @Test void draggingCannotLosePanelOrCoverChatInput() {
        var bounds = HudLayout.move(100, 104, 900, 900, 800, 450);
        assertEquals(700, bounds.x());
        assertEquals(322, bounds.y());
        var negative = HudLayout.move(100, 104, -100, -100, 800, 450);
        assertEquals(0, negative.x());
        assertEquals(0, negative.y());
    }

    @Test void savedBottomRightAnchorSurvivesWindowResize() {
        var position = HudLayout.normalize(new HudLayout.Bounds(700, 322, 100, 104), 800, 450);
        var resized = HudLayout.place(position, 100, 104, 400, 250, 8, 10);
        assertEquals(300, resized.x());
        assertEquals(122, resized.y());
    }

    @Test void tinyViewportCannotProduceNanOrNegativeCoordinates() {
        var bounds = HudLayout.move(100, 104, 80, 90, 60, 60);
        assertEquals(new HudLayout.Position(0, 0), HudLayout.normalize(bounds, 60, 60));
        assertEquals(0, bounds.x());
        assertEquals(0, bounds.y());
    }

    @Test void hudSettingsRoundTripWithoutChangingExistingPreferences() throws Exception {
        Path file = directory.resolve("aurora.properties");
        var config = new ClientConfig();
        config.menuKey(21);
        config.reducedMotion(true);
        config.hudPosition("armor_status", new HudLayout.Position(0.75, 0.5));
        config.hudPosition("fps_counter", new HudLayout.Position(0.25, 0.1));
        config.save(file);
        var restored = ClientConfig.load(file);
        assertEquals(config.hudPosition("armor_status"), restored.hudPosition("armor_status"));
        assertEquals(config.hudPosition("fps_counter"), restored.hudPosition("fps_counter"));
        assertEquals(21, restored.menuKey());
        assertTrue(restored.reducedMotion());
        restored.resetHudPosition("armor_status");
        restored.save(file);
        assertNull(ClientConfig.load(file).hudPosition("armor_status"));
    }

    @Test void oldConfigAndMalformedPositionsKeepOtherSettings() throws Exception {
        Path file = directory.resolve("aurora.properties");
        Files.writeString(file, "menu.key=21\nmodules.enabled=armor_status\n");
        assertNull(ClientConfig.load(file).hudPosition("armor_status"));
        Files.writeString(file, "hud.x=0\nhud.armor_status.x=NaN\nhud.armor_status.y=0.5\nhud.fps_counter.x=0.4\nhud.fps_counter.y=0.2\n", java.nio.file.StandardOpenOption.APPEND);
        var restored = ClientConfig.load(file);
        assertNull(restored.hudPosition("armor_status"));
        assertEquals(new HudLayout.Position(0.4, 0.2), restored.hudPosition("fps_counter"));
        assertEquals(21, restored.menuKey());
        assertTrue(restored.enabledModules().contains("armor_status"));
        assertThrows(IllegalArgumentException.class, () -> new HudLayout.Position(Double.POSITIVE_INFINITY, 0));
    }
}
