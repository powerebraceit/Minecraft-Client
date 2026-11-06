package dev.aurora.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ModuleRegistry {
    private final Map<String, ClientModule> modules = new LinkedHashMap<>();

    public void add(ClientModule module) {
        if (modules.putIfAbsent(module.id(), module) != null)
            throw new IllegalArgumentException("Duplicate module: " + module.id());
    }

    public ClientModule get(String id) { return modules.get(id); }
    public List<ClientModule> all() { return List.copyOf(modules.values()); }

    /** Searching spans all categories; an empty query shows the selected category. */
    public List<ClientModule> search(Category category, String query) {
        String text = query.strip().toLowerCase(Locale.ROOT);
        return modules.values().stream().filter(m -> text.isEmpty() ? m.category() == category :
            (m.name() + " " + m.description() + " " + m.category().label()).toLowerCase(Locale.ROOT).contains(text))
            .toList();
    }

    public static ModuleRegistry catalog() {
        ModuleRegistry r = new ModuleRegistry();
        r.add(new ClientModule("attack_cooldown", "Attack Cooldown", "Show when your next hit is ready", Category.COMBAT));
        r.add(new ClientModule("armor_status", "Armor Status", "Track armor durability at a glance", Category.COMBAT));
        r.add(new ClientModule("toggle_sprint", "Toggle Sprint", "Keep sprinting without holding the key", Category.MOVEMENT));
        r.add(new ClientModule("toggle_sneak", "Toggle Sneak", "Toggle sneaking with a single key press", Category.MOVEMENT));
        r.add(new ClientModule("zoom", "Zoom", "A smooth, adjustable close-up view", Category.RENDER));
        r.add(new ClientModule("coordinates", "Coordinates", "Display your position in the world", Category.RENDER));
        r.add(new ClientModule("fps_counter", "FPS Counter", "A minimal frames-per-second display", Category.RENDER));
        r.add(new ClientModule("block_info", "Block Info", "Inspect the block you are looking at", Category.WORLD));
        r.add(new ClientModule("biome_info", "Biome Info", "Display your current biome", Category.WORLD));
        r.add(new ClientModule("chat_timestamps", "Chat Timestamps", "Add local timestamps to chat messages", Category.MISC));
        r.add(new ClientModule("auto_builder", "Auto Builder", "Litematica layers, preview and restock chests", Category.WORLD));
        return r;
    }
}
