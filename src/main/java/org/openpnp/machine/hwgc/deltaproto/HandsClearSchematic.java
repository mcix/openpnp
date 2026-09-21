/*
 * Schematic drawing of the machine with a "keep hands clear" hazard warning, shown in the
 * startup prompt once the machine is ON, because the heads start moving as soon as the
 * operator connects and homes. Drawn in the same style as ControlPanelSchematic.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JComponent;

public class HandsClearSchematic extends JComponent {

    private static final Color PLATE = new Color(0x9a9da0);
    private static final Color PLATE_EDGE = new Color(0x6d7073);
    private static final Color BEAM = new Color(0x7d8084);
    private static final Color HEAD = new Color(0x3a3d40);
    private static final Color NOZZLE = new Color(0xa9acb0);
    private static final Color PCB = new Color(0x2e8b57);
    private static final Color ESTOP = new Color(0xe5392b);
    private static final Color POWER = new Color(0x2ecc71);
    private static final Color SKIN = new Color(0xf2c9a0);
    private static final Color SKIN_EDGE = new Color(0x9c6b3f);
    private static final Color HAZARD = new Color(0xffcc00);
    private static final Color CALLOUT = new Color(0xc62828);

    public HandsClearSchematic() {
        setPreferredSize(new Dimension(440, 250));
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        drawMachine(g);

        // Hand reaching into the work area, struck out with a prohibition sign
        Shape hand = handShape(176, 170, 0.8, -55, 60);
        g.setColor(SKIN);
        g.fill(hand);
        g.setColor(SKIN_EDGE);
        g.setStroke(new BasicStroke(1.5f));
        g.draw(hand);
        int nx = 152, ny = 150, nr = 38;
        g.setColor(CALLOUT);
        g.setStroke(new BasicStroke(7f));
        g.draw(new Ellipse2D.Double(nx - nr, ny - nr, nr * 2, nr * 2));
        double d = nr * Math.sqrt(0.5);
        g.draw(new java.awt.geom.Line2D.Double(nx - d, ny - d, nx + d, ny + d));

        // Hand crush hazard warning sign
        int tx = 325;
        drawHazardSign(g, tx, 6, 112);

        g.setFont(getFont().deriveFont(Font.BOLD, 14f));
        g.setColor(CALLOUT);
        ControlPanelSchematic.drawCentered(g, "Keep hands clear!", tx, 128);

        g.setFont(getFont().deriveFont(Font.BOLD, 12f));
        ControlPanelSchematic.drawCallout(g, nx + nr + 8, 158, 228, 162,
                "Stay out of the work area —", "the heads move without warning.");
        Color text = javax.swing.UIManager.getColor("Label.foreground");
        g.setColor(text != null ? text : Color.BLACK);
        g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
        g.drawString("Homing starts as soon as you click", 228, 212);
        g.drawString("Connect & Home.", 228, 228);
        g.dispose();
    }

    /** Front view of the machine: base with control panel, gantry, head and a board. */
    private static void drawMachine(Graphics2D g) {
        // Feet and base
        g.setColor(HEAD);
        g.fillRoundRect(24, 232, 26, 10, 4, 4);
        g.fillRoundRect(150, 232, 26, 10, 4, 4);
        g.setColor(PLATE);
        g.fillRoundRect(10, 178, 180, 58, 14, 14);
        g.setColor(PLATE_EDGE);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(10, 178, 180, 58, 14, 14);

        // Control panel on the base: emergency stop, power and lighting
        g.setColor(ESTOP);
        g.fillOval(30, 196, 22, 22);
        g.setColor(PLATE_EDGE);
        g.drawOval(30, 196, 22, 22);
        g.setColor(POWER);
        g.fillOval(62, 201, 12, 12);
        g.setColor(new Color(0x5aa9ff));
        g.fillOval(82, 201, 12, 12);

        // Gantry uprights and beam
        g.setColor(BEAM);
        g.fillRect(18, 58, 14, 120);
        g.fillRect(168, 58, 14, 120);
        g.setColor(PLATE_EDGE);
        g.drawRect(18, 58, 14, 120);
        g.drawRect(168, 58, 14, 120);
        g.setColor(BEAM);
        g.fillRoundRect(12, 40, 176, 24, 8, 8);
        g.setColor(PLATE_EDGE);
        g.drawRoundRect(12, 40, 176, 24, 8, 8);

        // Board on its rails
        g.setColor(HEAD);
        g.fillRect(50, 172, 6, 6);
        g.fillRect(124, 172, 6, 6);
        g.setColor(PCB);
        g.fillRect(48, 166, 84, 6);

        // Head with two nozzles
        g.setColor(HEAD);
        g.fillRoundRect(62, 32, 50, 62, 8, 8);
        g.setColor(NOZZLE);
        g.fillRect(73, 94, 6, 20);
        g.fillRect(95, 94, 6, 20);
        g.fill(triangle(72, 114, 80, 114, 76, 122));
        g.fill(triangle(94, 114, 102, 114, 98, 122));

        // Motion arrows either side of the head
        g.setColor(CALLOUT);
        g.setStroke(new BasicStroke(3f));
        g.drawLine(56, 80, 44, 80);
        g.fill(triangle(36, 80, 46, 74, 46, 86));
        g.drawLine(118, 80, 130, 80);
        g.fill(triangle(138, 80, 128, 74, 128, 86));
    }

    /** Yellow warning triangle with a hand being crushed between a press and its base. */
    private static void drawHazardSign(Graphics2D g, int cx, int top, int size) {
        double h = size * Math.sqrt(3) / 2;
        Path2D tri = triangle(cx, top + 4, cx - size / 2.0 + 3, top + h, cx + size / 2.0 - 3,
                top + h);
        g.setColor(HAZARD);
        g.fill(tri);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(tri);
        g.setColor(HAZARD);
        g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        double base = top + h;
        // Press coming down onto the fingers, with its base underneath
        g.setColor(Color.BLACK);
        g.fill(new Rectangle2D.Double(cx - 24, base - 50, 26, 14));
        g.fill(new Rectangle2D.Double(cx - 14, base - 62, 6, 12));
        g.fill(new Rectangle2D.Double(cx - 32, base - 16, 64, 5));
        g.fill(handShape(cx + 18, base - 27, 0.58, -90, 24));
    }

    /**
     * Open hand silhouette. The wrist is at (x, y); with rotation 0 the fingers point up,
     * negative angles turn it counter-clockwise. The forearm extends behind the wrist.
     */
    private static Shape handShape(double x, double y, double scale, double rotationDeg,
            double forearm) {
        Area hand = new Area(new RoundRectangle2D.Double(-14, -30, 28, 32, 12, 12));
        double[] tops = {-50, -57, -54, -46};
        for (int i = 0; i < 4; i++) {
            double fx = -14 + i * 7.27;
            hand.add(new Area(
                    new RoundRectangle2D.Double(fx, tops[i], 6.2, -22 - tops[i], 6.2, 6.2)));
        }
        Area thumb = new Area(new RoundRectangle2D.Double(-3.75, -22, 7.5, 26, 7.5, 7.5));
        AffineTransform tt = AffineTransform.getTranslateInstance(-11, -6);
        tt.rotate(Math.toRadians(-50));
        thumb.transform(tt);
        hand.add(thumb);
        hand.add(new Area(new RoundRectangle2D.Double(-10, -4, 20, forearm + 4, 6, 6)));

        AffineTransform t = AffineTransform.getTranslateInstance(x, y);
        t.rotate(Math.toRadians(rotationDeg));
        t.scale(scale, scale);
        hand.transform(t);
        return hand;
    }

    private static Path2D triangle(double x1, double y1, double x2, double y2, double x3,
            double y3) {
        Path2D p = new Path2D.Double();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        p.lineTo(x3, y3);
        p.closePath();
        return p;
    }
}
