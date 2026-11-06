package dev.aurora;

import dev.aurora.core.ClientConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public final class ModuleSettingsJUnitTest {
    @TempDir Path directory;
    @Test void perModuleSettingsSurviveReloadAndKeepExistingPreferences() throws Exception {
        Path file = directory.resolve("aurora.properties");
        ClientConfig config = new ClientConfig();
        config.menuKey(21);
        config.setting("armor_status", "background", "false");
        config.setting("armor_status", "text_shadow", "true");
        config.setting("fps_counter", "key", "11");
        config.save(file);
        var loaded = ClientConfig.load(file);
        assertFalse(loaded.flag("armor_status", "background", true));
        assertTrue(loaded.flag("armor_status", "text_shadow", false));
        assertTrue(loaded.flag("coordinates", "background", true));
        assertEquals(11, loaded.number("fps_counter", "key", 0));
        assertEquals(21, loaded.menuKey());
    }
    @Test void malformedValuesFallBackAndIdsAreValidated() {
        ClientConfig config = new ClientConfig();
        config.setting("armor_status", "background", "not-a-boolean");
        config.setting("zoom", "factor", "not-a-number");
        assertTrue(config.flag("armor_status", "background", true));
        assertEquals(4, config.number("zoom", "factor", 4));
        assertThrows(IllegalArgumentException.class, () -> config.setting("../invalid", "key", "1"));
    }
}
