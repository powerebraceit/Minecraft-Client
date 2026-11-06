package dev.aurora.core;

public enum Category {
    COMBAT("Combat"), MOVEMENT("Movement"), RENDER("Render"), WORLD("World"), MISC("Misc");

    private final String label;
    Category(String label) { this.label = label; }
    public String label() { return label; }
}
