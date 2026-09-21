/*
 * Schematic drawing of the machine's front control panel (emergency stop, power and
 * lighting buttons), shown in the startup prompt when the machine is not ON so the
 * operator knows which controls to check.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;

import javax.swing.JComponent;

public class ControlPanelSchematic extends JComponent {

    private static final Color PLATE = new Color(0x9a9da0);
    private static final Color PLATE_EDGE = new Color(0x6d7073);
    private static final Color ESTOP = new Color(0xe5392b);
    private static final Color ESTOP_EDGE = new Color(0xb3261b);
    private static final Color POWER = new Color(0x2ecc71);
    private static final Color LIGHTING = new Color(0x5aa9ff);
    private static final Color CALLOUT = new Color(0xc62828);

    public ControlPanelSchematic() {
        setPreferredSize(new Dimension(440, 250));
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // Mounting plate with its four screws
        int px = 10, py = 5, pw = 170, ph = 240;
        g.setColor(PLATE);
        g.fillRoundRect(px, py, pw, ph, 14, 14);
        g.setColor(PLATE_EDGE);
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(px, py, pw, ph, 14, 14);
        for (int[] s : new int[][] {{px + 12, py + 12}, {px + pw - 12, py + 12},
                {px + 12, py + ph - 12}, {px + pw - 12, py + ph - 12}}) {
            g.setColor(new Color(0xd0d2d4));
            g.fillOval(s[0] - 4, s[1] - 4, 8, 8);
            g.setColor(PLATE_EDGE);
            g.drawOval(s[0] - 4, s[1] - 4, 8, 8);
        }

        Font labelFont = getFont().deriveFont(Font.BOLD, 11f);
        g.setFont(labelFont);
        g.setColor(Color.BLACK);
        int cx = px + pw / 2;
        drawCentered(g, "Emergency Stop", cx, py + 34);

        // Emergency stop mushroom button with its three "twist" arrows
        int ex = cx, ey = py + 95, er = 44;
        g.setColor(ESTOP_EDGE);
        g.fillOval(ex - er - 3, ey - er - 3, (er + 3) * 2, (er + 3) * 2);
        g.setColor(ESTOP);
        g.fillOval(ex - er, ey - er, er * 2, er * 2);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        int ar = 27;
        for (int i = 0; i < 3; i++) {
            double start = 100 + i * 120;
            g.draw(new Arc2D.Double(ex - ar, ey - ar, ar * 2, ar * 2, start, -80, Arc2D.OPEN));
            drawArrowHead(g, ex, ey, ar, start - 80);
        }

        // Power and lighting push buttons
        int by = py + 200, br = 17;
        int powerX = px + 50, lightX = px + pw - 50;
        g.setColor(Color.BLACK);
        drawCentered(g, "Power", powerX, by - 27);
        drawCentered(g, "Lighting", lightX, by - 27);
        drawPushButton(g, powerX, by, br, POWER);
        drawPushButton(g, lightX, by, br, LIGHTING);

        // Callouts
        int tx = px + pw + 40;
        g.setFont(getFont().deriveFont(Font.BOLD, 12f));
        drawCallout(g, ex + er + 6, ey, tx, ey - 8,
                "1. Twist the red button clockwise", "    until it pops out (released).");
        drawCallout(g, powerX + br + 4, by + 6, tx, by - 2,
                "2. Press Power — it must light", "    up green. Then connect.");
        g.dispose();
    }

    private static void drawPushButton(Graphics2D g, int x, int y, int r, Color glow) {
        g.setColor(new Color(0xd9dbdd));
        g.fillOval(x - r - 5, y - r - 5, (r + 5) * 2, (r + 5) * 2);
        g.setColor(PLATE_EDGE);
        g.setStroke(new BasicStroke(1.2f));
        g.drawOval(x - r - 5, y - r - 5, (r + 5) * 2, (r + 5) * 2);
        g.setColor(glow);
        g.fillOval(x - r, y - r, r * 2, r * 2);
        g.setColor(new Color(255, 255, 255, 170));
        g.fillOval(x - r / 2, y - r / 2, r, r);
    }

    /** Arrow head at the end of an arc running clockwise, at the given angle in degrees. */
    private static void drawArrowHead(Graphics2D g, int cx, int cy, int r, double angleDeg) {
        double a = Math.toRadians(angleDeg);
        double x = cx + r * Math.cos(a);
        double y = cy - r * Math.sin(a);
        // Clockwise tangent in screen coordinates
        double tx = Math.sin(a), ty = Math.cos(a);
        double nx = Math.cos(a), ny = -Math.sin(a);
        Path2D head = new Path2D.Double();
        head.moveTo(x + tx * 11, y + ty * 11);
        head.lineTo(x + nx * 8, y + ny * 8);
        head.lineTo(x - nx * 8, y - ny * 8);
        head.closePath();
        g.fill(head);
    }

    private static void drawCallout(Graphics2D g, int fromX, int fromY, int textX, int textY,
            String line1, String line2) {
        g.setColor(CALLOUT);
        g.setStroke(new BasicStroke(1.8f));
        g.drawLine(textX - 6, fromY, fromX + 8, fromY);
        Path2D head = new Path2D.Double();
        head.moveTo(fromX, fromY);
        head.lineTo(fromX + 10, fromY - 5);
        head.lineTo(fromX + 10, fromY + 5);
        head.closePath();
        g.fill(head);
        Color text = javax.swing.UIManager.getColor("Label.foreground");
        g.setColor(text != null ? text : Color.BLACK);
        g.drawString(line1, textX, textY);
        g.drawString(line2, textX, textY + 16);
    }

    private static void drawCentered(Graphics2D g, String s, int cx, int y) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, cx - fm.stringWidth(s) / 2, y);
    }
}
