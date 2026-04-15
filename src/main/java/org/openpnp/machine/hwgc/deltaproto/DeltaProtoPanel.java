/*
 * DeltaProto control tab for the OpenPNP main UI.
 *
 * Lives in the isolated org.openpnp.machine.hwgc.deltaproto subpackage so that
 * upstream merges never touch DeltaProto code. Installed into MainFrame via a
 * single-line call in {@link DeltaProtoIntegration#install(org.openpnp.gui.MainFrame)}.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.prefs.Preferences;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

import org.openpnp.machine.hwgc.HwgcDvrCamera;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Machine;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

/**
 * Single controlled entry point from DeltaProto into OpenPNP. Currently
 * exposes the feeder importer; future DeltaProto actions (open/close project,
 * sync BOM, etc.) should be added here as additional buttons so that all
 * DeltaProto↔OpenPNP integration is visible from one place.
 */
public class DeltaProtoPanel extends JPanel {

    private static final String PREF_KEY_ENDPOINT = "deltaproto.feederEndpoint";
    private static final String DEFAULT_ENDPOINT =
            "http://localhost:8080/api/openpnp/feeders";

    private final Preferences prefs = Preferences.userNodeForPackage(DeltaProtoPanel.class);

    private final JTextField endpointField = new JTextField();
    private final JTextArea logArea = new JTextArea(10, 60);

    // Feeder layout fields — 4 corners × (x, y) + scale
    private final JTextField flX = new JTextField(8);
    private final JTextField flY = new JTextField(8);
    private final JTextField frX = new JTextField(8);
    private final JTextField frY = new JTextField(8);
    private final JTextField blX = new JTextField(8);
    private final JTextField blY = new JTextField(8);
    private final JTextField brX = new JTextField(8);
    private final JTextField brY = new JTextField(8);
    private final JTextField scaleField = new JTextField(6);

    public DeltaProtoPanel() {
        super(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(8, 8, 8, 8));

        loadLayoutIntoFields(FeederLayout.load());

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(buildHeader());
        top.add(Box.createVerticalStrut(8));
        top.add(buildConfigPanel());
        top.add(Box.createVerticalStrut(4));
        top.add(buildLayoutPanel());

        add(top, BorderLayout.NORTH);
        add(buildActionsPanel(), BorderLayout.CENTER);
        add(buildLogPanel(), BorderLayout.SOUTH);
    }

    // ── UI construction ──

    private JPanel buildHeader() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        p.add(new DeltaProtoLogo(32));
        JLabel title = new JLabel("DeltaProto");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        p.add(title);
        return p;
    }

    private JPanel buildConfigPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("Configuration"));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.gridy = 0;

        c.gridx = 0;
        c.weightx = 0;
        p.add(new JLabel("Feeder endpoint:"), c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        endpointField.setText(prefs.get(PREF_KEY_ENDPOINT, DEFAULT_ENDPOINT));
        p.add(endpointField, c);

        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        JButton saveBtn = new JButton("Save");
        saveBtn.addActionListener(e -> {
            prefs.put(PREF_KEY_ENDPOINT, endpointField.getText().trim());
            log("Endpoint saved.");
        });
        p.add(saveBtn, c);

        return p;
    }

    private JPanel buildLayoutPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("Feeder layout (corner positions)"));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        // Header row
        c.gridy = 0;
        c.gridx = 1;
        p.add(new JLabel("X", JLabel.CENTER), c);
        c.gridx = 2;
        p.add(new JLabel("Y", JLabel.CENTER), c);

        addCornerRow(p, c, 1, "Front-Left  (slot 1)",   flX, flY);
        addCornerRow(p, c, 2, "Front-Right (slot 25)",  frX, frY);
        addCornerRow(p, c, 3, "Back-Left   (slot 26)",  blX, blY);
        addCornerRow(p, c, 4, "Back-Right  (slot 50)",  brX, brY);

        // Scale + save
        c.gridy = 5;
        c.gridx = 0;
        p.add(new JLabel("Scale → mm:"), c);
        c.gridx = 1;
        p.add(scaleField, c);
        c.gridx = 2;
        JButton saveBtn = new JButton("Save layout");
        saveBtn.addActionListener(e -> saveLayout());
        p.add(saveBtn, c);

        return p;
    }

    private static void addCornerRow(JPanel p, GridBagConstraints c, int row,
            String label, JTextField xField, JTextField yField) {
        c.gridy = row;
        c.gridx = 0;
        p.add(new JLabel(label), c);
        c.gridx = 1;
        p.add(xField, c);
        c.gridx = 2;
        p.add(yField, c);
    }

    private void loadLayoutIntoFields(FeederLayout l) {
        flX.setText(Double.toString(l.flX));
        flY.setText(Double.toString(l.flY));
        frX.setText(Double.toString(l.frX));
        frY.setText(Double.toString(l.frY));
        blX.setText(Double.toString(l.blX));
        blY.setText(Double.toString(l.blY));
        brX.setText(Double.toString(l.brX));
        brY.setText(Double.toString(l.brY));
        scaleField.setText(Double.toString(l.scale));
    }

    private FeederLayout readLayoutFromFields() {
        FeederLayout l = new FeederLayout();
        l.flX = parseDouble(flX.getText(), FeederLayout.DEFAULT_FL_X);
        l.flY = parseDouble(flY.getText(), FeederLayout.DEFAULT_FL_Y);
        l.frX = parseDouble(frX.getText(), FeederLayout.DEFAULT_FR_X);
        l.frY = parseDouble(frY.getText(), FeederLayout.DEFAULT_FR_Y);
        l.blX = parseDouble(blX.getText(), FeederLayout.DEFAULT_BL_X);
        l.blY = parseDouble(blY.getText(), FeederLayout.DEFAULT_BL_Y);
        l.brX = parseDouble(brX.getText(), FeederLayout.DEFAULT_BR_X);
        l.brY = parseDouble(brY.getText(), FeederLayout.DEFAULT_BR_Y);
        l.scale = parseDouble(scaleField.getText(), FeederLayout.DEFAULT_SCALE);
        return l;
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim());
        }
        catch (Exception e) {
            return fallback;
        }
    }

    private void saveLayout() {
        FeederLayout l = readLayoutFromFields();
        l.save();
        log("Feeder layout saved.");
    }

    private JPanel buildActionsPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        p.setBorder(new TitledBorder("Actions"));

        JButton importBtn = new JButton("Import feeders");
        importBtn.addActionListener(e -> runImport(importBtn));
        p.add(importBtn);

        JButton reopenCamsBtn = new JButton("Reopen cameras");
        reopenCamsBtn.addActionListener(e -> runReopenCameras(reopenCamsBtn));
        p.add(reopenCamsBtn);

        return p;
    }

    private JPanel buildLogPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new TitledBorder("Log"));
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        p.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return p;
    }

    // ── Actions ──

    private void runImport(JButton trigger) {
        String url = endpointField.getText().trim();
        if (url.isEmpty()) {
            log("No endpoint configured.");
            return;
        }
        trigger.setEnabled(false);
        log("Importing feeders from " + url + " …");

        // Fetch on a background thread (HTTP can block), then apply mutations
        // and save on the EDT — OpenPNP's BeansBinding wiring throws
        // "Can not call this method on an unbound binding" if bean setters
        // are invoked off the EDT.
        new SwingWorker<DeltaProtoFeederImporter.Payload, Void>() {
            @Override
            protected DeltaProtoFeederImporter.Payload doInBackground() throws Exception {
                return DeltaProtoFeederImporter.fetchPayload(url);
            }

            @Override
            protected void done() {
                trigger.setEnabled(true);
                try {
                    DeltaProtoFeederImporter.Payload payload = get();
                    Machine machine = Configuration.get().getMachine();
                    DeltaProtoFeederImporter.ImportResult r =
                            DeltaProtoFeederImporter.apply(machine, payload, readLayoutFromFields());
                    Configuration.get().save();
                    log(r.toString());
                    for (String w : r.warnings) {
                        log("  ! " + w);
                    }
                }
                catch (Exception ex) {
                    log("Import failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void runReopenCameras(JButton trigger) {
        trigger.setEnabled(false);
        log("Reopening HWGC DVR cameras …");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                HwgcDvrCamera.reopenAll();
                return null;
            }

            @Override
            protected void done() {
                trigger.setEnabled(true);
                try {
                    get();
                    log("Cameras reopened.");
                }
                catch (Exception ex) {
                    log("Reopen failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void log(String line) {
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }
}
