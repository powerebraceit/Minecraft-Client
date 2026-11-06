package dev.aurora.core;

/** Relative positions keep HUD panels visible after GUI scaling or window resizing. */
public final class HudLayout {
    private HudLayout() { }

    public record Position(double x, double y) {
        public Position {
            if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1)
                throw new IllegalArgumentException("HUD position must be finite and between 0 and 1");
        }
    }

    public record Bounds(int x, int y, int width, int height) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    // Keep panels above the chat input strip.
    public static int maxY(int viewportHeight, int panelHeight) { return Math.max(0, viewportHeight - 24 - panelHeight); }
    public static int maxX(int viewportWidth, int panelWidth) { return Math.max(0, viewportWidth - panelWidth); }

    public static Bounds place(Position saved, int width, int height, int viewportWidth, int viewportHeight, int defaultX, int defaultY) {
        int x = saved == null ? defaultX : (int) Math.round(saved.x * maxX(viewportWidth, width));
        int y = saved == null ? defaultY : (int) Math.round(saved.y * maxY(viewportHeight, height));
        return move(width, height, x, y, viewportWidth, viewportHeight);
    }

    public static Bounds move(int width, int height, double x, double y, int viewportWidth, int viewportHeight) {
        return new Bounds((int) Math.round(Math.clamp(x, 0, maxX(viewportWidth, width))),
            (int) Math.round(Math.clamp(y, 0, maxY(viewportHeight, height))), width, height);
    }

    public static Position normalize(Bounds bounds, int viewportWidth, int viewportHeight) {
        int maxX = maxX(viewportWidth, bounds.width), maxY = maxY(viewportHeight, bounds.height);
        return new Position(maxX == 0 ? 0 : Math.clamp((double) bounds.x / maxX, 0, 1),
            maxY == 0 ? 0 : Math.clamp((double) bounds.y / maxY, 0, 1));
    }
}
