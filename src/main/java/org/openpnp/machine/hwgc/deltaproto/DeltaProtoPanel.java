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
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import javax.swing.Timer;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ComboBoxEditor;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

import org.openpnp.gui.MainFrame;
import org.openpnp.machine.hwgc.HwgcDvrCamera;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.spi.Machine;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Single controlled entry point from DeltaProto into OpenPNP. Currently
 * exposes the feeder importer; future DeltaProto actions (open/close project,
 * sync BOM, etc.) should be added here as additional buttons so that all
 * DeltaProto↔OpenPNP integration is visible from one place.
 */
public class DeltaProtoPanel extends JPanel {

    private static final String PREF_KEY_ENDPOINT = "deltaproto.feederEndpoint";
    private static final String PREF_KEY_JOB_ENDPOINT = "deltaproto.jobEndpoint";
    private static final String DEFAULT_ENDPOINT =
            "https://deltaproto.com/api/openpnp/feeders?machine=Buddy%202";
    private static final String DEFAULT_JOB_ENDPOINT =
            "https://deltaproto.com/api/openpnp/jobs";
    private static final String DEFAULT_PROJECT_SEARCH_ENDPOINT =
            "https://deltaproto.com/api/openpnp/projectorders";

    private final Preferences prefs = Preferences.userNodeForPackage(DeltaProtoPanel.class);

    private final JTextField endpointField = new JTextField();
    private final JTextArea logArea = new JTextArea(10, 60);

    // Job-import UI
    private final DefaultComboBoxModel<ProjectOrderItem> projectModel = new DefaultComboBoxModel<>();
    private final JComboBox<ProjectOrderItem> projectCombo = new JComboBox<>(projectModel);
    private final Timer searchDebounce = new Timer(250, e -> runProjectSearch());
    private SwingWorker<List<ProjectOrderItem>, Void> activeSearchWorker;

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
        top.add(Box.createVerticalStrut(4));
        top.add(buildJobPanel());

        add(top, BorderLayout.NORTH);
        add(buildActionsPanel(), BorderLayout.CENTER);
        add(buildLogPanel(), BorderLayout.SOUTH);

        // Initial fetch so the dropdown isn't empty before the user types.
        searchDebounce.setRepeats(false);
        runProjectSearch();
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

    private JPanel buildJobPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("Job import"));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.gridy = 0;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0;
        c.weightx = 0;
        p.add(new JLabel("Project order:"), c);

        c.gridx = 1;
        c.weightx = 1;
        projectCombo.setEditable(true);
        // The combo box is editable so the user can type to filter; the typed
        // text drives the debounced search against /api/openpnp/projectorders.
        ComboBoxEditor editor = projectCombo.getEditor();
        if (editor.getEditorComponent() instanceof JTextComponent) {
            JTextComponent tc = (JTextComponent) editor.getEditorComponent();
            tc.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { searchDebounce.restart(); }
                @Override public void removeUpdate(DocumentEvent e) { searchDebounce.restart(); }
                @Override public void changedUpdate(DocumentEvent e) { searchDebounce.restart(); }
            });
        }
        p.add(projectCombo, c);

        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        JButton importBtn = new JButton("Import job");
        importBtn.addActionListener(e -> runJobImport(importBtn));
        p.add(importBtn, c);

        return p;
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

    // ── Job import actions ──

    private String currentSearchText() {
        Object editorItem = projectCombo.getEditor().getItem();
        if (editorItem == null) {
            return "";
        }
        if (editorItem instanceof ProjectOrderItem) {
            return ((ProjectOrderItem) editorItem).displayName;
        }
        return editorItem.toString();
    }

    private void runProjectSearch() {
        // Don't fire a search for selections we made ourselves; only when the
        // user is clearly typing free text. We treat any text shorter than the
        // current selection's displayName as filter input.
        String text = currentSearchText();
        Object selected = projectCombo.getSelectedItem();
        if (selected instanceof ProjectOrderItem
                && text.equals(((ProjectOrderItem) selected).displayName)) {
            return;
        }
        if (activeSearchWorker != null && !activeSearchWorker.isDone()) {
            activeSearchWorker.cancel(true);
        }
        activeSearchWorker = new SwingWorker<List<ProjectOrderItem>, Void>() {
            @Override
            protected List<ProjectOrderItem> doInBackground() throws Exception {
                return searchProjectOrders(text);
            }

            @Override
            protected void done() {
                try {
                    List<ProjectOrderItem> results = get();
                    String preserved = currentSearchText();
                    projectModel.removeAllElements();
                    for (ProjectOrderItem item : results) {
                        projectModel.addElement(item);
                    }
                    // Keep the user's typing in the editor — replacing the
                    // model resets the editor to the first item otherwise.
                    projectCombo.setSelectedItem(null);
                    projectCombo.getEditor().setItem(preserved);
                    if (projectCombo.isPopupVisible() == false && results.size() > 0) {
                        projectCombo.showPopup();
                    }
                }
                catch (Exception ex) {
                    // Cancellation is expected when the user keeps typing —
                    // don't pollute the log.
                    if (!(ex instanceof java.util.concurrent.CancellationException)) {
                        log("Project search failed: " + ex.getMessage());
                    }
                }
            }
        };
        activeSearchWorker.execute();
    }

    private List<ProjectOrderItem> searchProjectOrders(String query) throws Exception {
        String base = prefs.get("deltaproto.projectSearchEndpoint", DEFAULT_PROJECT_SEARCH_ENDPOINT);
        String url = base
                + (base.contains("?") ? "&" : "?")
                + "q=" + URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }
        List<ProjectOrderItem> items = new Gson().fromJson(response.body(),
                new TypeToken<List<ProjectOrderItem>>() {}.getType());
        return items != null ? items : new ArrayList<>();
    }

    private void runJobImport(JButton trigger) {
        // Resolve the project order id: prefer a structured selection, fall
        // back to the raw text the user typed.
        String projectOrderId;
        Object selected = projectCombo.getSelectedItem();
        if (selected instanceof ProjectOrderItem) {
            ProjectOrderItem item = (ProjectOrderItem) selected;
            projectOrderId = item.internalName != null && !item.internalName.isEmpty()
                    ? item.internalName : item.name;
        }
        else {
            projectOrderId = currentSearchText();
        }
        if (projectOrderId == null || projectOrderId.isBlank()) {
            log("No project order selected.");
            return;
        }

        String base = prefs.get(PREF_KEY_JOB_ENDPOINT, DEFAULT_JOB_ENDPOINT);
        String url = DeltaProtoJobImporter.buildJobUrl(base, projectOrderId.trim());

        trigger.setEnabled(false);
        log("Importing job " + projectOrderId + " from " + url + " …");

        new SwingWorker<DeltaProtoJobImporter.Payload, Void>() {
            @Override
            protected DeltaProtoJobImporter.Payload doInBackground() throws Exception {
                return DeltaProtoJobImporter.fetchPayload(url);
            }

            @Override
            protected void done() {
                trigger.setEnabled(true);
                try {
                    DeltaProtoJobImporter.Payload payload = get();
                    DeltaProtoJobImporter.JobBuildResult built =
                            DeltaProtoJobImporter.buildJob(payload);
                    if (built.job == null) {
                        log("Job import failed: empty payload");
                        return;
                    }
                    Configuration.get().save();
                    MainFrame.get().getJobTab().setJob(built.job);
                    log(built.result.toString());
                    for (String w : built.result.warnings) {
                        log("  ! " + w);
                    }
                }
                catch (Exception ex) {
                    log("Job import failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    /** DTO matching {@code OpenPnPController.ProjectOrderSummaryDao}. Public
     *  so Gson can deserialise it through reflection. */
    public static class ProjectOrderItem {
        public String id;
        public String internalName;
        public String name;
        public String displayName;

        @Override
        public String toString() {
            return displayName != null ? displayName
                    : (internalName != null ? internalName
                    : (name != null ? name : ""));
        }
    }

    private void log(String line) {
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }
}
