package io.github.profetgit.cyanotype.ponder;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.profetgit.cyanotype.TestBootstrap;
import io.github.profetgit.cyanotype.ui.PreviewRaster;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * A designer's tool, not a test: draws the lessons in {@code src/main/resources/assets/cyanotype/ponders} as stills (map colours
 * instead of textures, a plain arrow for the cursor, the caption as text) into build/ponder-stills, so a lesson can be judged
 * without starting the game. Run: {@code ./gradlew test --offline --tests '*PonderStillsTool*' -Dponder.stills=place,edit}
 * (a list of ids, or "all"); {@code -Dponder.step=1.0} sets the seconds between stills, {@code -Dponder.times=10.5,11.2} picks the moments.
 */
class PonderStillsTool {
    @Test
    void draw() throws IOException {
        String which = System.getProperty("ponder.stills", System.getenv("PONDER_STILLS"));
        assumeTrue(which != null && !which.isBlank(), "set -Dponder.stills=<ids> to draw lessons");
        TestBootstrap.init();
        Path dir = Path.of("src/main/resources/assets/cyanotype/ponders");
        double step = Double.parseDouble(System.getProperty("ponder.step", System.getenv().getOrDefault("PONDER_STEP", "1.0")));
        List<String> ids = new ArrayList<>();
        if (which.equals("all")) {
            try (var s = Files.list(dir)) {
                s.filter(p -> p.toString().endsWith(".json") && !p.getFileName().toString().equals("index.json")).forEach(p -> ids.add(p.getFileName().toString().replace(".json", "")));
            }
            ids.sort(null);
        } else {
            ids.addAll(List.of(which.split(",")));
        }
        Path out = Path.of("build/ponder-stills");
        Files.createDirectories(out);
        for (String id : ids) {
            Scene sc = SceneReader.read(Files.readString(dir.resolve(id + ".json")));
            sheet(sc, step, out.resolve(id + ".png"));
            System.out.println("PONDER wrote " + out.resolve(id + ".png"));
        }
    }

    static void sheet(Scene sc, double step, Path file) throws IOException {
        int fw = 640, fh = 360, cols = 3;
        List<Double> times = new ArrayList<>();
        String at = System.getProperty("ponder.times", System.getenv("PONDER_TIMES"));
        if (at != null && !at.isBlank()) {
            for (String t : at.split(",")) times.add(Double.parseDouble(t));
        } else {
            for (double t = 0; t < sc.duration - 0.01; t += step) times.add(t);
            times.add(sc.duration - 0.02);
        }
        int rows = (times.size() + cols - 1) / cols;
        BufferedImage sheet = new BufferedImage(cols * fw, rows * fh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = sheet.createGraphics();
        StageRaster raster = new StageRaster();
        for (int i = 0; i < times.size(); i++) {
            double t = times.get(i);
            Snapshot snap = Evaluator.at(sc, t);
            int ss = 2;
            int[] px = raster.render(sc, snap, PonderLooks.INSTANCE, fw * ss, fh * ss, ss);
            int[] argb = PreviewRaster.resolveArgb(new PreviewRaster.Frame(fw * ss, fh * ss, px, new int[0]), ss, fw, fh);
            BufferedImage im = new BufferedImage(fw, fh, BufferedImage.TYPE_INT_ARGB);
            im.setRGB(0, 0, fw, fh, argb, 0, fw);
            Graphics2D d = im.createGraphics();
            d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            StageRaster.Camera cam = raster.lastCamera();
            // the cursor: a white arrow at its point (world points only; panel and screen spots are put where the screen would)
            if (snap.cursor != null) {
                double[] a = spot(sc, snap, cam, snap.cursor.from(), fw, fh, ss), b = spot(sc, snap, cam, snap.cursor.to(), fw, fh, ss);
                if (a != null && b != null) {
                    double x = a[0] + (b[0] - a[0]) * snap.cursor.k(), y = a[1] + (b[1] - a[1]) * snap.cursor.k();
                    d.setColor(new Color(255, 255, 255, (int) (255 * snap.cursor.alpha())));
                    int[] xs = {(int) x, (int) x, (int) x + 7, (int) x + 12}, ys = {(int) y, (int) y + 17, (int) y + 12, (int) y + 12};
                    d.fillPolygon(new int[]{(int) x, (int) x, (int) x + 5, (int) x + 9}, new int[]{(int) y, (int) y + 14, (int) y + 10, (int) y + 10}, 4);
                    if (snap.cursor.clickAge() < 0.45) {
                        d.setColor(new Color(127, 227, 255, 200));
                        int r = (int) (6 + 22 * snap.cursor.clickAge() / 0.45);
                        d.drawOval((int) x - r, (int) y - r, 2 * r, 2 * r);
                    }
                }
            }
            for (StageRaster.TextMark m : raster.labels()) {
                d.setFont(new Font("SansSerif", Font.PLAIN, 14));
                int w = d.getFontMetrics().stringWidth(m.text());
                int x = (int) (m.x() / ss) - w / 2, y = (int) (m.y() / ss);
                d.setColor(new Color(10, 27, 48, 220));
                d.fillRect(x - 3, y - 12, w + 6, 17);
                d.setColor(Color.WHITE);
                d.drawString(m.text(), x, y);
            }
            d.setFont(new Font("SansSerif", Font.BOLD, 15));
            d.setColor(new Color(10, 27, 48, 200));
            d.fillRect(0, fh - 56, fw, 56);
            d.setColor(new Color(127, 227, 255));
            String title = snap.caption == null ? "" : snap.caption.title();
            d.drawString(String.format(java.util.Locale.ROOT, "t=%.1f  step %d/%d  %s", t, snap.step + 1, snap.steps, title), 8, fh - 38);
            d.setFont(new Font("SansSerif", Font.PLAIN, 14));
            d.setColor(Color.WHITE);
            d.drawString(snap.caption == null ? "" : snap.caption.text(), 8, fh - 20);
            StringBuilder chips = new StringBuilder();
            for (Snapshot.ChipRow c : snap.chips) chips.append(c.lit() ? "[*" : "[").append(c.key()).append(": ").append(c.action()).append("] ");
            d.setColor(new Color(255, 200, 87));
            d.drawString(chips.toString(), 8, fh - 4);
            d.dispose();
            g.drawImage(im, (i % cols) * fw, (i / cols) * fh, null);
        }
        g.dispose();
        ImageIO.write(sheet, "png", file.toFile());
    }

    /** Where a cursor point lands in a frame (panel points are guessed at the middle of the left side). */
    private static double[] spot(Scene sc, Snapshot snap, StageRaster.Camera cam, Scene.Pt p, int fw, int fh, int ss) {
        return switch (p.kind()) {
            case Scene.Pt.WORLD -> {
                double[] s = cam.project(p.at()[0], p.at()[1], p.at()[2]);
                yield new double[]{s[0] / ss, s[1] / ss};
            }
            case Scene.Pt.SCREEN -> new double[]{p.at()[0] * fw, p.at()[1] * fh};
            default -> new double[]{fw * 0.2, fh * (0.2 + 0.07 * p.row())};
        };
    }
}
