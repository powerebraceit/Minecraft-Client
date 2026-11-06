package dev.aurora.core;

import java.util.Objects;

/** A module must have a real game implementation before it can be enabled. */
public final class ClientModule {
    public interface Effect {
        void enable();
        void disable();
    }

    private final String id;
    private final String name;
    private final String description;
    private final Category category;
    private Effect effect;
    private boolean enabled;

    public ClientModule(String id, String name, String description, Category category) {
        if (!id.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid module id");
        this.id = id;
        this.name = Objects.requireNonNull(name);
        this.description = Objects.requireNonNull(description);
        this.category = Objects.requireNonNull(category);
    }

    public String id() { return id; }
    public String name() { return name; }
    public String description() { return description; }
    public Category category() { return category; }
    public boolean enabled() { return enabled; }
    public boolean available() { return effect != null; }

    public void bind(Effect implementation) {
        if (enabled) throw new IllegalStateException("Disable before replacing an implementation");
        effect = Objects.requireNonNull(implementation);
    }

    public void setEnabled(boolean value) {
        if (enabled == value) return;
        if (effect == null) throw new IllegalStateException("Game implementation not installed: " + id);
        if (value) effect.enable(); else effect.disable();
        enabled = value;
    }
}
