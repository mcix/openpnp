/*
 * DeltaProto logo rendered with Java2D so the deltaproto subpackage stays
 * self-contained — no Batik, no PNG resource, nothing to check into the
 * upstream resources tree. Shape coordinates are transcribed directly from
 * the 1112×1112 source SVG and scaled to the requested component height.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JComponent;

class DeltaProtoLogo extends JComponent {

    private static final double SRC = 1112.0;
    private static final Color RED = new Color(216, 42, 48);

    private final int targetHeight;

    DeltaProtoLogo(int targetHeight) {
        this.targetHeight = targetHeight;
        int w = (int) Math.round(targetHeight * (SRC / SRC));
        setPreferredSize(new Dimension(w, targetHeight));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            double s = targetHeight / SRC;
            g2.scale(s, s);

            // Red rounded background
            g2.setColor(RED);
            g2.fill(new RoundRectangle2D.Double(
                    97.836, 0.93, 915.819, 1110.141, 163.638, 163.638));

            // White vertical bars
            g2.setColor(Color.WHITE);
            g2.fill(new Rectangle2D.Double(424.184, 106.923, 138.535, 506.722));
            g2.fill(new Rectangle2D.Double(549.238, 496.96, 138.535, 506.722));

            // White outer circles
            fillEllipse(g2, 283.789, 555.93, 280.325, 277.536);
            fillEllipse(g2, 827.238, 556.465, 280.325, 277.536);

            // Red inner circles (approximated from the SVG paths)
            g2.setColor(RED);
            fillEllipse(g2, 826.5, 555.5, 138.5, 138.5);
            fillEllipse(g2, 285.2, 556.0, 138.2, 138.5);
        }
        finally {
            g2.dispose();
        }
    }

    private static void fillEllipse(Graphics2D g2, double cx, double cy, double rx, double ry) {
        g2.fill(new Ellipse2D.Double(cx - rx, cy - ry, rx * 2, ry * 2));
    }
}
