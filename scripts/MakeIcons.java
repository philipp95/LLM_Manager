import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Builds the app's icons from the logo master, docs/brand/logo.png. Run from the repository root:
 *
 *     java scripts/MakeIcons.java
 *
 * The master is a rounded square on white; the white outside it becomes transparent. Writes the
 * Android launcher foreground for every density (the logo fills the 72dp visible part of the
 * 108dp adaptive-icon layer), the app's in-app mark, and the dashboard's logo.
 */
public class MakeIcons {
    private static final String RES = "android/app/src/main/res/";
    private static final String[] DENSITIES = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    private static final double[] SCALES = {1, 1.5, 2, 3, 4};

    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        BufferedImage logo = clearCorners(ImageIO.read(new File(args.length > 0 ? args[0] : "docs/brand/logo.png")));
        for (int i = 0; i < DENSITIES.length; i++) {
            int layer = (int) Math.round(108 * SCALES[i]);
            int visible = (int) Math.round(72 * SCALES[i]);
            BufferedImage canvas = new BufferedImage(layer, layer, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D g = canvas.createGraphics();
            int offset = (layer - visible) / 2;
            g.drawImage(scaled(logo, visible), offset, offset, null);
            g.dispose();
            write(canvas, RES + "drawable-" + DENSITIES[i] + "/ic_launcher_foreground.png");
        }
        write(scaled(logo, 160), RES + "drawable-nodpi/logo_mark.png");
        write(scaled(logo, 192), "server/api/src/main/resources/dashboard/logo.png");
    }

    /**
     * Makes the white outside the rounded square transparent. Its corner radius is found on the
     * diagonal; pixels near or outside the edge get an alpha from how white they are, and their
     * colour un-blended from white, so the anti-aliased rim keeps no pale fringe.
     */
    static BufferedImage clearCorners(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        int corner = 0;
        while (corner < Math.min(w, h) / 2 && minChannel(source.getRGB(corner, corner)) >= 128) corner++;
        double radius = corner * Math.sqrt(2) / (Math.sqrt(2) - 1);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = source.getRGB(x, y) & 0xffffff;
                double dx = Math.max(Math.max(radius - x, 0), x - (w - 1 - radius));
                double dy = Math.max(Math.max(radius - y, 0), y - (h - 1 - radius));
                if (Math.hypot(dx, dy) <= radius - 3) {
                    out.setRGB(x, y, 0xff000000 | rgb);
                    continue;
                }
                double alpha = Math.min(1, Math.max(0, (255 - minChannel(rgb)) / 215.0));
                if (alpha == 0) continue;
                int r = unblend((rgb >> 16) & 255, alpha);
                int gr = unblend((rgb >> 8) & 255, alpha);
                int b = unblend(rgb & 255, alpha);
                out.setRGB(x, y, ((int) Math.round(alpha * 255) << 24) | (r << 16) | (gr << 8) | b);
            }
        }
        return out;
    }

    private static int minChannel(int rgb) {
        return Math.min((rgb >> 16) & 255, Math.min((rgb >> 8) & 255, rgb & 255));
    }

    private static int unblend(int channel, double alpha) {
        return (int) Math.round(Math.min(255, Math.max(0, (channel - (1 - alpha) * 255) / alpha)));
    }

    /** High-quality downscale: halve step by step, then a final bicubic step, in premultiplied alpha. */
    static BufferedImage scaled(BufferedImage source, int size) {
        BufferedImage current = resize(source, source.getWidth());
        int width = current.getWidth();
        while (width / 2 >= size) {
            width /= 2;
            current = resize(current, width);
        }
        return resize(current, size);
    }

    private static BufferedImage resize(BufferedImage source, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    private static void write(BufferedImage image, String path) throws IOException {
        BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        File file = new File(path);
        file.getParentFile().mkdirs();
        ImageIO.write(argb, "png", file);
        System.out.println(path + " " + image.getWidth() + "px");
    }
}
