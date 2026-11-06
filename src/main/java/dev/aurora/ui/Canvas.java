package dev.aurora.ui;

/** Coordinates are logical GUI pixels. Colors use ARGB. Implement with the game's renderer. */
public interface Canvas {
    void roundedRect(float x, float y, float width, float height, float radius, int color);
    void text(String value, float x, float y, float size, int color);
    void clip(float x, float y, float width, float height);
    void unclip();
}
