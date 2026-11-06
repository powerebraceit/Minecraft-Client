package dev.aurora;

import dev.aurora.core.ClientConfig;
import dev.aurora.core.ModuleRegistry;
import dev.aurora.ui.Canvas;
import dev.aurora.ui.ClickGui;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Offline design preview only; this does not simulate Minecraft integration. */
public final class DesignPreview implements Canvas {
    private final Graphics2D graphics;

    private DesignPreview(Graphics2D graphics) { this.graphics = graphics; }

    @Override public void roundedRect(float x, float y, float width, float height, float radius, int color) {
        graphics.setColor(new Color(color, true));
        graphics.fill(new RoundRectangle2D.Float(x, y, width, height, radius * 2, radius * 2));
    }

    @Override public void text(String value, float x, float y, float size, int color) {
        graphics.setColor(new Color(color, true));
        graphics.setFont(new Font(Font.SANS_SERIF, value.equals("AURORA") ? Font.BOLD : Font.PLAIN, 12).deriveFont(size));
        graphics.drawString(value, x, y + size * 0.85f);
    }

    @Override public void clip(float x, float y, float width, float height) {
        graphics.setClip(new java.awt.geom.Rectangle2D.Float(x, y, width, height));
    }
    @Override public void unclip() { graphics.setClip(null); }

    public static void main(String[] args) throws Exception {
        int width = args.length > 1 ? Integer.parseInt(args[1]) : 960;
        int height = args.length > 2 ? Integer.parseInt(args[2]) : 600;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xff1d2338)); g.fillRect(0, 0, width, height);
            ClientConfig config = new ClientConfig(); config.reducedMotion(true);
            ClickGui gui = new ClickGui(ModuleRegistry.catalog(), config, () -> { });
            gui.render(new DesignPreview(g), width, height, -1, -1, 1);
        } finally { g.dispose(); }
        Path output = Path.of(args.length == 0 ? "build/design-preview.png" : args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        ImageIO.write(image, "png", output.toFile());
        System.out.println("Offline design preview: " + output);
    }
}
