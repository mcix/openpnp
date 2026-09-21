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
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;
import javax.swing.Timer;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.openpnp.gui.MainFrame;

import org.openpnp.machine.hwgc.HwgcDriver;
import org.openpnp.machine.hwgc.HwgcDvrCamera;

import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Driver;
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
    private static final String PREF_KEY_DEF_BOARD_X = "deltaproto.defaultBoardX";
    private static final String PREF_KEY_DEF_BOARD_Y = "deltaproto.defaultBoardY";
    private static final String PREF_KEY_DEF_BOARD_Z = "deltaproto.defaultBoardZ";
    private static final double DEFAULT_BOARD_X = 200.000;
    private static final double DEFAULT_BOARD_Y = 160.000;
    private static final double DEFAULT_BOARD_Z = -110.000;
    private static final String PREF_KEY_PLACEMENT_RETRIES = "deltaproto.placementRetries";
    private static final int DEFAULT_PLACEMENT_RETRIES = 3;
    // No machine= parameter: the server derives the machine from the token
    // (X-Buddy-Token) that ServerLinkConfig.authorize() adds to the request.
    private static final String DEFAULT_ENDPOINT =
            "https://deltaproto.com/api/openpnp/feeders";
    // This machine was "Buddy 2" before the second machine arrived; it is now
    // the master "Buddy 2.1" (the slave is "Buddy 2.2").
    private static final String[] OLD_MACHINE_PARAMS = {"machine=Buddy%202", "machine=Buddy+2",
            "machine=Buddy 2"};
    private static final String RENAMED_MACHINE_PARAM = "machine=Buddy%202.1";
    private static final String DEFAULT_JOB_ENDPOINT =
            "https://deltaproto.com/api/openpnp/jobs";
    private static final String DEFAULT_PROJECT_SEARCH_ENDPOINT =
            "https://deltaproto.com/api/openpnp/projectorders";

    private final Preferences prefs = Preferences.userNodeForPackage(DeltaProtoPanel.class);

    private final JTextField endpointField = new JTextField();
    private final JTextArea logArea = new JTextArea(10, 60);

    // Server link (ServerLink): settings fields + live status in the header.
    private final JTextField serverUrlField = new JTextField();
    private final javax.swing.JPasswordField serverTokenField = new javax.swing.JPasswordField();
    private final JTextField serverInterfaceField = new JTextField(12);
    private final JTextField serverLanPortField = new JTextField(6);
    private final JLabel serverStatusLabel = new JLabel(" ");

    // Machine link (master ↔ slave over the LAN) + PCB hand-over.
    private final JLabel machineLinkLabel = new JLabel(" ");
    private final JButton linkTestBtn = new JButton("Test communication");
    private final JButton transferBtn = new JButton("Transfer PCB → slave");
    private final JTextField transferDelayField = new JTextField(6);
    private final JTextField transferSpeedField = new JTextField(4);
    private final JTextField outDelayField = new JTextField(5);

    // Job-import UI. Deliberately NOT an editable JComboBox: Swing
    // reconfigures a combo's editor on every model/selection change,
    // replacing and select-all-ing the text the user is typing. A plain
    // text field with a non-focusable popup list never touches the text.
    private final JTextField projectField = new JTextField();
    private final DefaultListModel<ProjectOrderItem> suggestionModel = new DefaultListModel<>();
    private final JList<ProjectOrderItem> suggestionList = new JList<>(suggestionModel);
    private final JPopupMenu suggestionPopup = new JPopupMenu();
    // Last item explicitly picked from the list; cleared as soon as the
    // user edits the text again.
    private ProjectOrderItem selectedProjectOrder;
    private final Timer searchDebounce = new Timer(250, e -> runProjectSearch());
    private SwingWorker<List<ProjectOrderItem>, Void> activeSearchWorker;
    // Live readout of the loaded job's board location(s), polled because the
    // job (and its BoardLocations) can be replaced wholesale at any time.
    private final JLabel pcbPositionLabel = new JLabel(" ");
    private final Timer pcbPositionRefresh = new Timer(1000, e -> {
        refreshPcbPosition();
        refreshServerStatus();
    });
    // True while we set the field text ourselves (accepting a suggestion);
    // the DocumentListener must ignore those events or we'd search again.
    private boolean suppressSearch = false;

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
    private final JTextField zField = new JTextField(8);

    // New-project defaults — seed PCB position for a freshly imported job
    private final JTextField defBoardX = new JTextField(8);
    private final JTextField defBoardY = new JTextField(8);
    private final JTextField defBoardZ = new JTextField(8);
    private final JTextField retryAttemptsField = new JTextField(4);

    // Plankje layout fields — pin1 + pin8 positions, pick Z, cover Z
    private final JTextField pjPin1X = new JTextField(8);
    private final JTextField pjPin1Y = new JTextField(8);
    private final JTextField pjPin8X = new JTextField(8);
    private final JTextField pjPin8Y = new JTextField(8);
    private final JTextField pjZ = new JTextField(8);
    private final JTextField pjCoverZ = new JTextField(8);
    private final JTextField pjFeederPinField = new JTextField(4);

    public DeltaProtoPanel() {
        super(new BorderLayout(8, 8));
        setBorder(new EmptyBorder(8, 8, 8, 8));

        loadLayoutIntoFields(FeederLayout.load());

        // ── Main tab ──
        JPanel mainTab = new JPanel(new BorderLayout(8, 8));

        JPanel mainTop = new JPanel();
        mainTop.setLayout(new BoxLayout(mainTop, BoxLayout.Y_AXIS));
        mainTop.add(buildTrackControlPanel());
        mainTop.add(Box.createVerticalStrut(4));
        mainTop.add(buildJobPanel());

        mainTab.add(mainTop, BorderLayout.NORTH);
        mainTab.add(buildActionsPanel(), BorderLayout.CENTER);

        // ── Settings tab ──
        JPanel settingsTab = new JPanel();
        settingsTab.setLayout(new BoxLayout(settingsTab, BoxLayout.Y_AXIS));
        settingsTab.add(buildServerPanel());
        settingsTab.add(Box.createVerticalStrut(4));
        settingsTab.add(buildMachineLinkPanel());
        settingsTab.add(Box.createVerticalStrut(4));
        settingsTab.add(buildConfigPanel());
        settingsTab.add(Box.createVerticalStrut(4));
        settingsTab.add(buildNewProjectDefaultsPanel());
        settingsTab.add(Box.createVerticalStrut(4));
        settingsTab.add(buildLayoutPanel());
        settingsTab.add(Box.createVerticalStrut(4));
        settingsTab.add(buildPlankjePanel());

        // ── Tabbed pane ──
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Main", mainTab);
        // The settings sections are taller than the tab on the machine's
        // screen; without a scroll pane the bottom ones are simply cut off.
        JPanel settingsHolder = new WidthTrackingPanel();
        settingsHolder.add(settingsTab, BorderLayout.NORTH);
        JScrollPane settingsScroll = new JScrollPane(settingsHolder,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        settingsScroll.setBorder(null);
        settingsScroll.getVerticalScrollBar().setUnitIncrement(16);
        tabs.addTab("Settings", settingsScroll);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(buildHeader());
        top.add(Box.createVerticalStrut(8));

        add(top, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(buildLogPanel(), BorderLayout.SOUTH);

        // Initial fetch so the dropdown isn't empty before the user types.
        searchDebounce.setRepeats(false);
        runProjectSearch();

        refreshPcbPosition();
        pcbPositionRefresh.start();

        // Server link: replay what happened before this panel existed, then
        // follow it live. Listener callbacks arrive on the link's threads.
        ServerLink link = ServerLink.get();
        for (String line : link.getLogBacklog()) {
            log(line);
        }
        link.addListener(new ServerLink.Listener() {
            @Override public void onChanged() {
                SwingUtilities.invokeLater(() -> refreshServerStatus());
            }
            @Override public void onLog(String line) {
                SwingUtilities.invokeLater(() -> log(line));
            }
        });
        refreshServerStatus();
    }

    // ── UI construction ──

    private JPanel buildHeader() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        p.add(new DeltaProtoLogo(32));
        JLabel title = new JLabel("DeltaProto");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        p.add(title);
        p.add(serverStatusLabel);
        return p;
    }

    private JPanel buildTrackControlPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        p.setBorder(new TitledBorder("Track control"));

        // Track+ (widen): hold to move, release to stop
        JButton trackPlusBtn = new JButton("Track +");
        trackPlusBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                try {
                    HwgcDriver driver = findHwgcDriver();
                    if (driver != null) {
                        driver.sendTrackConstantSpeed(0, 7);
                        log("Track+ moving…");
                    }
                } catch (Exception ex) {
                    log("Track+ failed: " + ex.getMessage());
                }
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                try {
                    HwgcDriver driver = findHwgcDriver();
                    if (driver != null) {
                        driver.sendTrackStopMove();
                        log("Track+ stopped.");
                    }
                } catch (Exception ex) {
                    log("Track stop failed: " + ex.getMessage());
                }
            }
        });
        p.add(trackPlusBtn);

        // Track- (narrow): hold to move, release to stop
        JButton trackMinusBtn = new JButton("Track -");
        trackMinusBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                try {
                    HwgcDriver driver = findHwgcDriver();
                    if (driver != null) {
                        driver.sendTrackConstantSpeed(1, 7);
                        log("Track- moving…");
                    }
                } catch (Exception ex) {
                    log("Track- failed: " + ex.getMessage());
                }
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                try {
                    HwgcDriver driver = findHwgcDriver();
                    if (driver != null) {
                        driver.sendTrackStopMove();
                        log("Track- stopped.");
                    }
                } catch (Exception ex) {
                    log("Track stop failed: " + ex.getMessage());
                }
            }
        });
        p.add(trackMinusBtn);

        // Clamp
        JButton clampBtn = new JButton("Clamp");
        clampBtn.addActionListener(e -> {
            try {
                HwgcDriver driver = findHwgcDriver();
                if (driver != null) {
                    driver.sendExecutePlywood(0, true);
                    log("Clamp executed.");
                }
            } catch (Exception ex) {
                log("Clamp failed: " + ex.getMessage());
            }
        });
        p.add(clampBtn);

        // Unclamp
        JButton unclampBtn = new JButton("Unclamp");
        unclampBtn.addActionListener(e -> {
            try {
                HwgcDriver driver = findHwgcDriver();
                if (driver != null) {
                    driver.sendExecutePlywood(0, false);
                    log("Unclamp executed.");
                }
            } catch (Exception ex) {
                log("Unclamp failed: " + ex.getMessage());
            }
        });
        p.add(unclampBtn);

        // Inboard
        JButton inboardBtn = new JButton("Inboard");
        inboardBtn.addActionListener(e -> {
            try {
                HwgcDriver driver = findHwgcDriver();
                if (driver != null) {
                    driver.sendInBoard(BoardTransfer.getVelocity());
                    log("Inboard executed.");
                }
            } catch (Exception ex) {
                log("Inboard failed: " + ex.getMessage());
            }
        });
        p.add(inboardBtn);

        // Outboard
        JButton outboardBtn = new JButton("Outboard");
        outboardBtn.addActionListener(e -> {
            try {
                HwgcDriver driver = findHwgcDriver();
                if (driver != null) {
                    saveTransferSettings();
                    int delay = BoardTransfer.getOutDelayTenths();
                    driver.sendOutBoard(BoardTransfer.getVelocity(), delay);
                    log("Outboard executed" + (delay > 0
                            ? " (out-sensor delay " + delay / 10.0 + " s)." : "."));
                }
            } catch (Exception ex) {
                log("Outboard failed: " + ex.getMessage());
            }
        });
        p.add(outboardBtn);

        // Hand the board over the conveyor to the slave: the slave confirms it
        // is ready, then this machine feeds out while the slave feeds in.
        transferBtn.addActionListener(e -> {
            saveTransferSettings();
            ServerLink.get().getBoardTransfer().transferToSlave();
        });
        p.add(transferBtn);

        return p;
    }

    private HwgcDriver findHwgcDriver() {
        for (Driver d : Configuration.get().getMachine().getDrivers()) {
            if (d instanceof HwgcDriver) {
                return (HwgcDriver) d;
            }
        }
        log("No HwgcDriver found.");
        return null;
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
        endpointField.setText(migrateEndpoint(prefs.get(PREF_KEY_ENDPOINT, DEFAULT_ENDPOINT)));
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

    /**
     * One-time rename of a stored feeder endpoint that still names this
     * machine "Buddy 2". Only an exact old name is rewritten ("Buddy 2.1" /
     * "Buddy 2.2" also start with it and must be left alone).
     */
    private String migrateEndpoint(String endpoint) {
        for (String old : OLD_MACHINE_PARAMS) {
            int i = endpoint.indexOf(old);
            if (i < 0) {
                continue;
            }
            int end = i + old.length();
            if (end == endpoint.length() || endpoint.charAt(end) == '&') {
                String migrated = endpoint.substring(0, i) + RENAMED_MACHINE_PARAM
                        + endpoint.substring(end);
                prefs.put(PREF_KEY_ENDPOINT, migrated);
                log("Feeder endpoint: machine renamed Buddy 2 → Buddy 2.1.");
                return migrated;
            }
        }
        return endpoint;
    }

    /** Settings section for the DeltaProto server link: URL, machine token, LAN. */
    private JPanel buildServerPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("Server (machine token from /dashboard/parts/buddysettings)"));

        ServerLinkConfig cfg = ServerLinkConfig.load();
        serverUrlField.setText(cfg.baseUrl);
        serverTokenField.setText(cfg.token);
        serverInterfaceField.setText(cfg.localIpInterface);
        serverInterfaceField.setToolTipText(
                "Optional network interface name; empty = auto-detect the office LAN address");
        serverLanPortField.setText(Integer.toString(cfg.lanPort));
        serverLanPortField.setToolTipText(
                "TCP port of the master ↔ slave LAN link; must be the same on both machines");

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridy = 0;
        c.gridx = 0;
        c.weightx = 0;
        p.add(new JLabel("Server URL:"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.gridwidth = 3;
        p.add(serverUrlField, c);
        c.gridwidth = 1;

        c.gridy = 1;
        c.gridx = 0;
        c.weightx = 0;
        p.add(new JLabel("Machine token:"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.gridwidth = 3;
        p.add(serverTokenField, c);
        c.gridwidth = 1;

        c.gridy = 2;
        c.gridx = 0;
        c.weightx = 0;
        p.add(new JLabel("LAN interface:"), c);
        c.gridx = 1;
        c.weightx = 1;
        p.add(serverInterfaceField, c);
        c.gridx = 2;
        c.weightx = 0;
        p.add(new JLabel("LAN port:"), c);
        c.gridx = 3;
        p.add(serverLanPortField, c);

        c.gridy = 0;
        c.gridx = 4;
        c.gridheight = 2;
        c.fill = GridBagConstraints.NONE;
        JButton saveBtn = new JButton("Save & reconnect");
        saveBtn.addActionListener(e -> {
            ServerLinkConfig saved = new ServerLinkConfig();
            saved.baseUrl = serverUrlField.getText().trim().isEmpty()
                    ? ServerLinkConfig.DEFAULT_BASE_URL : serverUrlField.getText();
            saved.token = new String(serverTokenField.getPassword());
            saved.localIpInterface = serverInterfaceField.getText();
            try {
                saved.lanPort = Integer.parseInt(serverLanPortField.getText().trim());
            }
            catch (Exception ex) {
                saved.lanPort = ServerLinkConfig.DEFAULT_LAN_PORT;
                serverLanPortField.setText(Integer.toString(saved.lanPort));
            }
            saved.save();
            // Show the normalised base URL (a pasted wss://…/ws URL is reduced to it).
            serverUrlField.setText(ServerLinkConfig.load().baseUrl);
            log("Server settings saved — reconnecting.");
            ServerLink.get().reconfigure();
        });
        p.add(saveBtn, c);

        c.gridy = 2;
        c.gridheight = 1;
        JButton refetchBtn = new JButton("Refetch feeders");
        refetchBtn.setToolTipText("Refetch this machine's feeder config from the server now");
        refetchBtn.addActionListener(e -> ServerLink.get().refetchFeeders());
        p.add(refetchBtn, c);

        return p;
    }

    /**
     * Settings section showing whether the two Buddy machines can talk to each
     * other over the LAN, with a round-trip test and the PCB hand-over timing.
     */
    private JPanel buildMachineLinkPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("Machine link (master ↔ slave over the LAN)"));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;

        c.gridy = 0;
        c.gridx = 0;
        c.gridwidth = 5;
        c.weightx = 1;
        p.add(machineLinkLabel, c);
        c.gridwidth = 1;
        c.weightx = 0;

        c.gridy = 1;
        c.gridx = 0;
        p.add(new JLabel("Slave feed-in delay (ms):"), c);
        c.gridx = 1;
        transferDelayField.setText(Integer.toString(BoardTransfer.getFeedInDelayMs()));
        transferDelayField.setToolTipText("How long after the master's feed out the slave starts"
                + " its feed in. 0 = both conveyors start together.");
        p.add(transferDelayField, c);
        c.gridx = 2;
        p.add(new JLabel("Track speed step (0-9):"), c);
        c.gridx = 3;
        transferSpeedField.setText(Integer.toString(BoardTransfer.getVelocity()));
        transferSpeedField.setToolTipText("Speed step in the IN_BOARD / OUT_BOARD command."
                + " 0 = what the HWGC test panel sends.");
        p.add(transferSpeedField, c);

        c.gridy = 2;
        c.gridx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(new JLabel("Out-sensor stop delay (s):"), c);
        c.gridx = 1;
        outDelayField.setText(Double.toString(BoardTransfer.getOutDelayTenths() / 10.0));
        outDelayField.setToolTipText("Keep the conveyor running this long after the board reaches"
                + " the out sensor (0.1 s steps, the vendor's \"track delay\"; SmtProgram"
                + " default 0.5). Used by Outboard and by Transfer PCB.");
        p.add(outDelayField, c);
        c.gridy = 1;
        c.gridx = 4;
        c.fill = GridBagConstraints.NONE;
        JButton saveBtn = new JButton("Save");
        saveBtn.addActionListener(e -> {
            saveTransferSettings();
            log("PCB transfer settings saved.");
        });
        p.add(saveBtn, c);

        c.gridy = 3;
        c.gridx = 0;
        c.gridwidth = 2;
        linkTestBtn.setToolTipText("Send a test message to the other machine and wait for its"
                + " answer; the round-trip time is logged");
        linkTestBtn.addActionListener(e -> {
            int sent = ServerLink.get().getLanLink().testCommunication();
            if (sent == 0) {
                log("! LAN test: no link to the other machine — nothing sent.");
            }
        });
        p.add(linkTestBtn, c);

        return p;
    }

    private void saveTransferSettings() {
        try {
            BoardTransfer.setFeedInDelayMs(Integer.parseInt(transferDelayField.getText().trim()));
        }
        catch (Exception ex) {
            // keep the stored value
        }
        try {
            BoardTransfer.setVelocity(Integer.parseInt(transferSpeedField.getText().trim()));
        }
        catch (Exception ex) {
            // keep the stored value
        }
        try {
            BoardTransfer.setOutDelayTenths((int) Math.round(
                    Double.parseDouble(outDelayField.getText().trim().replace(',', '.')) * 10));
        }
        catch (Exception ex) {
            // keep the stored value
        }
        transferDelayField.setText(Integer.toString(BoardTransfer.getFeedInDelayMs()));
        transferSpeedField.setText(Integer.toString(BoardTransfer.getVelocity()));
        outDelayField.setText(Double.toString(BoardTransfer.getOutDelayTenths() / 10.0));
    }

    private static String dot(boolean ok) {
        return "<font color='" + (ok ? "#1b7f2a" : "#c62828") + "'>●</font> ";
    }

    private void refreshMachineLink() {
        ServerLink link = ServerLink.get();
        BuddyLanLink lan = link.getLanLink();
        PeerView me = link.getSelf();
        boolean linked = lan.isLinked();

        StringBuilder sb = new StringBuilder("<html>");
        if (me == null) {
            sb.append("Not connected to the server yet — role and peers unknown.");
        }
        else {
            sb.append("This machine: <b>").append(escape(String.valueOf(me.name)))
                    .append("</b> · ").append(escape(String.valueOf(me.role))).append("<br>");
            boolean anyPartner = false;
            for (PeerView peer : link.getPeers()) {
                if (!peer.isMaster() && !peer.isSlave()) {
                    continue;
                }
                anyPartner = true;
                sb.append(dot(Boolean.TRUE.equals(peer.connected)))
                        .append(escape(String.valueOf(peer.name))).append(" · ")
                        .append(escape(String.valueOf(peer.role))).append(" · ")
                        .append(peer.localIp != null ? escape(peer.localIp) : "no LAN address yet")
                        .append(Boolean.TRUE.equals(peer.connected)
                                ? " · online at the server" : " · offline at the server")
                        .append("<br>");
            }
            if (!anyPartner) {
                sb.append("No master/slave partner configured on the server.<br>");
            }
            List<String> lines = lan.describe();
            if (lines.isEmpty()) {
                sb.append(PeerView.ROLE_STANDALONE.equalsIgnoreCase(me.role)
                        ? "Standalone: no LAN link." : "LAN link: waiting for the partner's address.");
            }
            for (String line : lines) {
                sb.append(dot(line.endsWith(": linked"))).append("LAN — ").append(escape(line))
                        .append("<br>");
            }
        }
        sb.append("</html>");
        String text = sb.toString();
        if (!text.equals(machineLinkLabel.getText())) {
            machineLinkLabel.setText(text);
        }
        linkTestBtn.setEnabled(linked);
        transferBtn.setEnabled(linked && lan.isMaster());
        transferBtn.setToolTipText(!lan.isMaster() ? "Only the master hands boards over"
                : linked ? "Feed the board out here and in on the slave"
                        : "No LAN link to the slave (see Settings → Machine link)");
    }

    /** Header readout of the server link; called on any ServerLink change and once a second. */
    private void refreshServerStatus() {
        refreshMachineLink();
        ServerLink link = ServerLink.get();
        ServerLink.State state = link.getState();
        PeerView me = link.getSelf();

        String color;
        switch (state) {
            case CONNECTED:
                color = "#1b7f2a";
                break;
            case CONNECTING:
            case OFFLINE:
                color = "#9e9e9e";
                break;
            default:
                color = "#c62828";
                break;
        }
        StringBuilder sb = new StringBuilder("<html>");
        if (me != null && me.name != null) {
            sb.append("<b>").append(escape(me.name)).append("</b> · ")
                    .append(escape(String.valueOf(me.role))).append(" &nbsp; ");
        }
        sb.append("<font color='").append(color).append("'>●</font> Server: ");
        if (state == ServerLink.State.CONNECTED) {
            long s = (System.currentTimeMillis() - link.getConnectedSince()) / 1000;
            sb.append("connected · ").append(s < 120 ? s + " s" : (s / 60) + " min");
        }
        else {
            sb.append(escape(state.text));
        }
        for (String line : link.getLanLink().describe()) {
            boolean up = line.endsWith(": linked");
            sb.append(" &nbsp; <font color='").append(up ? "#1b7f2a" : "#c62828")
                    .append("'>●</font> ").append(escape(line));
        }
        sb.append("</html>");
        String text = sb.toString();
        if (!text.equals(serverStatusLabel.getText())) {
            serverStatusLabel.setText(text);
        }
        String detail = link.getStateDetail();
        serverStatusLabel.setToolTipText(detail != null ? detail
                : (link.getPeers().isEmpty() ? null : "Peers: " + link.getPeers()));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Settings section for the PCB position a freshly imported job starts at. */
    private JPanel buildNewProjectDefaultsPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("New project defaults (PCB position, mm)"));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridy = 0;

        defBoardX.setText(Double.toString(prefs.getDouble(PREF_KEY_DEF_BOARD_X, DEFAULT_BOARD_X)));
        defBoardY.setText(Double.toString(prefs.getDouble(PREF_KEY_DEF_BOARD_Y, DEFAULT_BOARD_Y)));
        defBoardZ.setText(Double.toString(prefs.getDouble(PREF_KEY_DEF_BOARD_Z, DEFAULT_BOARD_Z)));

        c.gridx = 0;
        p.add(new JLabel("X:"), c);
        c.gridx = 1;
        p.add(defBoardX, c);
        c.gridx = 2;
        p.add(new JLabel("Y:"), c);
        c.gridx = 3;
        p.add(defBoardY, c);
        c.gridx = 4;
        p.add(new JLabel("Z:"), c);
        c.gridx = 5;
        p.add(defBoardZ, c);

        // Row 1: placement retry attempts, applied to the job processor's
        // maxPlacementRetries on every job import (job error handling is
        // always set to Defer so failed placements are retried instead of
        // stopping the machine).
        c.gridy = 1;
        c.gridx = 0;
        c.gridwidth = 3;
        p.add(new JLabel("Placement retry attempts:"), c);
        c.gridx = 3;
        c.gridwidth = 1;
        retryAttemptsField.setText(Integer.toString(
                prefs.getInt(PREF_KEY_PLACEMENT_RETRIES, DEFAULT_PLACEMENT_RETRIES)));
        p.add(retryAttemptsField, c);

        c.gridx = 6;
        c.gridy = 0;
        c.gridheight = 2;
        c.fill = GridBagConstraints.NONE;
        JButton saveBtn = new JButton("Save defaults");
        saveBtn.addActionListener(e -> {
            prefs.putDouble(PREF_KEY_DEF_BOARD_X,
                    parseDouble(defBoardX.getText(), DEFAULT_BOARD_X));
            prefs.putDouble(PREF_KEY_DEF_BOARD_Y,
                    parseDouble(defBoardY.getText(), DEFAULT_BOARD_Y));
            prefs.putDouble(PREF_KEY_DEF_BOARD_Z,
                    parseDouble(defBoardZ.getText(), DEFAULT_BOARD_Z));
            prefs.putInt(PREF_KEY_PLACEMENT_RETRIES, placementRetryAttempts());
            log("New project defaults saved.");
        });
        p.add(saveBtn, c);

        return p;
    }

    /** The configured number of placement retry attempts (min 0). */
    private int placementRetryAttempts() {
        int fallback = prefs.getInt(PREF_KEY_PLACEMENT_RETRIES, DEFAULT_PLACEMENT_RETRIES);
        try {
            return Math.max(0, Integer.parseInt(retryAttemptsField.getText().trim()));
        }
        catch (Exception e) {
            return fallback;
        }
    }

    /** The configured default PCB position for a newly imported job. */
    private Location defaultBoardLocation() {
        return new Location(LengthUnit.Millimeters,
                parseDouble(defBoardX.getText(),
                        prefs.getDouble(PREF_KEY_DEF_BOARD_X, DEFAULT_BOARD_X)),
                parseDouble(defBoardY.getText(),
                        prefs.getDouble(PREF_KEY_DEF_BOARD_Y, DEFAULT_BOARD_Y)),
                parseDouble(defBoardZ.getText(),
                        prefs.getDouble(PREF_KEY_DEF_BOARD_Z, DEFAULT_BOARD_Z)),
                0.0);
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

        // Scale
        c.gridy = 5;
        c.gridx = 0;
        p.add(new JLabel("Scale → mm:"), c);
        c.gridx = 1;
        p.add(scaleField, c);

        // Pick Z (applied to every imported feeder)
        c.gridy = 6;
        c.gridx = 0;
        p.add(new JLabel("Pick Z (mm):"), c);
        c.gridx = 1;
        p.add(zField, c);
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
        zField.setText(Double.toString(l.z));
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
        l.z = parseDouble(zField.getText(), FeederLayout.DEFAULT_Z);
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

    /** Settings section for the plankje (covered strip carrier) layout.
     *  Teach pin 1 and pin 8 with the down-looking camera; pins 2..7 are
     *  interpolated for the DeltaProtoCoveredStripFeederV1 feeders. */
    private JPanel buildPlankjePanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(new TitledBorder("Plankje layout (8 covered strips, pin 1 → pin 8 = "
                + PlankjeLayout.PIN1_TO_PIN8_MM + " mm)"));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        PlankjeLayout l = PlankjeLayout.load();
        pjPin1X.setText(Double.toString(l.pin1X));
        pjPin1Y.setText(Double.toString(l.pin1Y));
        pjPin8X.setText(Double.toString(l.pin8X));
        pjPin8Y.setText(Double.toString(l.pin8Y));
        pjZ.setText(Double.toString(l.z));
        pjCoverZ.setText(Double.toString(l.coverZ));

        // Header row
        c.gridy = 0;
        c.gridx = 1;
        p.add(new JLabel("X", JLabel.CENTER), c);
        c.gridx = 2;
        p.add(new JLabel("Y", JLabel.CENTER), c);

        addPlankjePinRow(p, c, 1, "Pin 1", pjPin1X, pjPin1Y);
        addPlankjePinRow(p, c, 2, "Pin 8", pjPin8X, pjPin8Y);

        c.gridy = 3;
        c.gridx = 3;
        c.gridwidth = 2;
        // Fill pin 8 from pin 1 + the nominal 73.5 mm along X.
        JButton fromPin1Btn = new JButton("Pin 8 = Pin 1 + " + PlankjeLayout.PIN1_TO_PIN8_MM + " mm X");
        fromPin1Btn.addActionListener(e -> {
            double x = parseDouble(pjPin1X.getText(), PlankjeLayout.DEFAULT_PIN1_X);
            double y = parseDouble(pjPin1Y.getText(), PlankjeLayout.DEFAULT_PIN1_Y);
            pjPin8X.setText(Double.toString(x + PlankjeLayout.PIN1_TO_PIN8_MM));
            pjPin8Y.setText(Double.toString(y));
        });
        p.add(fromPin1Btn, c);
        c.gridwidth = 1;

        c.gridy = 4;
        c.gridx = 0;
        p.add(new JLabel("Pick Z (mm):"), c);
        c.gridx = 1;
        p.add(pjZ, c);
        c.gridx = 2;
        p.add(new JLabel("Cover Z (mm):"), c);
        c.gridx = 3;
        p.add(pjCoverZ, c);

        c.gridy = 5;
        c.gridx = 3;
        c.gridwidth = 2;
        JButton saveBtn = new JButton("Save plankje");
        saveBtn.addActionListener(e -> {
            PlankjeLayout saved = readPlankjeFromFields();
            saved.save();
            log("Plankje layout saved.");
        });
        p.add(saveBtn, c);
        c.gridwidth = 1;

        // Feeder management: create one covered strip feeder per pin, and
        // re-sync all existing ones from this panel's configuration.
        c.gridy = 6;
        c.gridx = 0;
        p.add(new JLabel("Feeder pin (" + PlankjeLayout.PIN_FIRST + "-"
                + PlankjeLayout.PIN_LAST + "):"), c);
        c.gridx = 1;
        pjFeederPinField.setText("1");
        p.add(pjFeederPinField, c);
        c.gridx = 2;
        JButton createFeederBtn = new JButton("Create feeder");
        createFeederBtn.setToolTipText(
                "Create a DeltaProtoCoveredStripFeederV1 on this pin from the plankje config");
        createFeederBtn.addActionListener(e -> createPlankjeFeeder());
        p.add(createFeederBtn, c);
        c.gridx = 3;
        c.gridwidth = 2;
        JButton updateFeedersBtn = new JButton("Update all feeders from config");
        updateFeedersBtn.setToolTipText(
                "Save the plankje config and re-sync every DeltaProtoCoveredStripFeederV1 to it");
        updateFeedersBtn.addActionListener(e -> updatePlankjeFeeders());
        p.add(updateFeedersBtn, c);
        c.gridwidth = 1;

        return p;
    }

    /** Name convention for the 8 plankje feeders. */
    private static String plankjeFeederName(int pin) {
        return "Plankje-" + pin;
    }

    /** Create a DeltaProtoCoveredStripFeederV1 for the pin entered in the
     *  panel, using the (saved) plankje configuration. */
    private void createPlankjeFeeder() {
        // The feeders derive their positions from the saved layout, so make
        // sure what is on screen is what they will use.
        PlankjeLayout l = readPlankjeFromFields();
        l.save();

        int pin;
        try {
            pin = Integer.parseInt(pjFeederPinField.getText().trim());
        }
        catch (Exception ex) {
            log("Create feeder: invalid pin number '" + pjFeederPinField.getText() + "'.");
            return;
        }
        if (pin < PlankjeLayout.PIN_FIRST || pin > PlankjeLayout.PIN_LAST) {
            log("Create feeder: pin " + pin + " outside "
                    + PlankjeLayout.PIN_FIRST + ".." + PlankjeLayout.PIN_LAST + ".");
            return;
        }

        Machine machine = Configuration.get().getMachine();
        for (org.openpnp.spi.Feeder f : machine.getFeeders()) {
            if (f instanceof DeltaProtoCoveredStripFeederV1
                    && ((DeltaProtoCoveredStripFeederV1) f).getPin() == pin) {
                log("Create feeder: pin " + pin + " already has feeder '"
                        + f.getName() + "'. Not creating a duplicate.");
                return;
            }
        }

        try {
            DeltaProtoCoveredStripFeederV1 feeder = new DeltaProtoCoveredStripFeederV1();
            feeder.setPin(pin);
            feeder.setName(plankjeFeederName(pin));
            // Mirror the pin position into the base feeder location for the
            // standard OpenPNP UI; the pick location itself is derived live
            // from the plankje layout.
            feeder.setLocation(l.pinLocation(pin));
            if (!Configuration.get().getParts().isEmpty()) {
                // Placeholder part, like FeedersPanel does on manual create.
                feeder.setPart(Configuration.get().getParts().get(0));
            }
            machine.addFeeder(feeder);
            refreshFeedersTab();
            log("Created feeder '" + feeder.getName() + "' on pin " + pin + " at "
                    + String.format(Locale.US, "X %.3f Y %.3f",
                            feeder.getOriginLocation().getX(),
                            feeder.getOriginLocation().getY()));
        }
        catch (Exception ex) {
            log("Create feeder failed: " + ex.getMessage());
        }
    }

    /** Save the plankje config and re-sync every DeltaProtoCoveredStripFeederV1
     *  (location mirror + name) to it. */
    private void updatePlankjeFeeders() {
        PlankjeLayout l = readPlankjeFromFields();
        l.save();

        int count = 0;
        for (org.openpnp.spi.Feeder f : Configuration.get().getMachine().getFeeders()) {
            if (!(f instanceof DeltaProtoCoveredStripFeederV1)) {
                continue;
            }
            DeltaProtoCoveredStripFeederV1 feeder = (DeltaProtoCoveredStripFeederV1) f;
            // Re-fires the derived-location property changes so any open
            // wizard picks up the new plankje geometry too.
            feeder.setPin(feeder.getPin());
            feeder.setLocation(l.pinLocation(feeder.getPin()));
            count++;
        }
        refreshFeedersTab();
        log("Plankje layout saved; updated " + count + " covered strip feeder(s).");
    }

    private void refreshFeedersTab() {
        SwingUtilities.invokeLater(() -> {
            if (MainFrame.get() != null && MainFrame.get().getFeedersTab() != null) {
                MainFrame.get().getFeedersTab().refresh();
            }
        });
    }

    /** A pin teach row: X/Y fields plus set-from-camera / move-camera-to. */
    private void addPlankjePinRow(JPanel p, GridBagConstraints c, int row,
            String label, JTextField xField, JTextField yField) {
        c.gridy = row;
        c.gridx = 0;
        p.add(new JLabel(label), c);
        c.gridx = 1;
        p.add(xField, c);
        c.gridx = 2;
        p.add(yField, c);

        c.gridx = 3;
        JButton captureBtn = new JButton("Set from camera");
        captureBtn.setToolTipText("Copy the current down-camera X/Y into " + label);
        captureBtn.addActionListener(e -> org.openpnp.util.UiUtils.submitUiMachineTask(() -> {
            Location loc = MainFrame.get().getMachineControls().getSelectedTool()
                    .getHead().getDefaultCamera().getLocation()
                    .convertToUnits(LengthUnit.Millimeters);
            SwingUtilities.invokeLater(() -> {
                xField.setText(String.format(java.util.Locale.US, "%.3f", loc.getX()));
                yField.setText(String.format(java.util.Locale.US, "%.3f", loc.getY()));
                log(label + " set from camera: X " + xField.getText() + "  Y " + yField.getText());
            });
        }));
        p.add(captureBtn, c);

        c.gridx = 4;
        JButton moveBtn = new JButton("Camera →");
        moveBtn.setToolTipText("Move the down-camera over the entered " + label + " position");
        moveBtn.addActionListener(e -> org.openpnp.util.UiUtils.submitUiMachineTask(() -> {
            Location target = new Location(LengthUnit.Millimeters,
                    parseDouble(xField.getText(), 0),
                    parseDouble(yField.getText(), 0),
                    0, 0);
            org.openpnp.util.MovableUtils.moveToLocationAtSafeZ(
                    MainFrame.get().getMachineControls().getSelectedTool()
                            .getHead().getDefaultCamera(), target);
        }));
        p.add(moveBtn, c);
    }

    private PlankjeLayout readPlankjeFromFields() {
        PlankjeLayout l = new PlankjeLayout();
        l.pin1X = parseDouble(pjPin1X.getText(), PlankjeLayout.DEFAULT_PIN1_X);
        l.pin1Y = parseDouble(pjPin1Y.getText(), PlankjeLayout.DEFAULT_PIN1_Y);
        l.pin8X = parseDouble(pjPin8X.getText(), PlankjeLayout.DEFAULT_PIN8_X);
        l.pin8Y = parseDouble(pjPin8Y.getText(), PlankjeLayout.DEFAULT_PIN8_Y);
        l.z = parseDouble(pjZ.getText(), PlankjeLayout.DEFAULT_Z);
        l.coverZ = parseDouble(pjCoverZ.getText(), PlankjeLayout.DEFAULT_COVER_Z);
        return l;
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
        // Typing drives the debounced search against
        // /api/openpnp/projectorders; results show in a popup list below.
        projectField.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                if (!suppressSearch) {
                    selectedProjectOrder = null;
                    searchDebounce.restart();
                }
            }
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        });

        // Non-focusable so the field keeps focus (and the caret) while the
        // popup is open; the list is driven by arrow keys and mouse only.
        suggestionPopup.setFocusable(false);
        suggestionPopup.setLayout(new BorderLayout());
        suggestionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        suggestionList.setVisibleRowCount(10);
        suggestionPopup.add(new JScrollPane(suggestionList), BorderLayout.CENTER);

        suggestionList.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                int i = suggestionList.locationToIndex(e.getPoint());
                if (i >= 0) {
                    acceptSuggestion(suggestionModel.get(i));
                }
            }
        });

        projectField.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int size = suggestionModel.getSize();
                if (!suggestionPopup.isVisible()) {
                    if (e.getKeyCode() == KeyEvent.VK_DOWN && size > 0) {
                        showSuggestionPopup();
                        e.consume();
                    }
                    return;
                }
                int idx = suggestionList.getSelectedIndex();
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN:
                        if (size > 0) {
                            int next = Math.min(idx + 1, size - 1);
                            suggestionList.setSelectedIndex(next);
                            suggestionList.ensureIndexIsVisible(next);
                        }
                        e.consume();
                        break;
                    case KeyEvent.VK_UP:
                        if (size > 0) {
                            int prev = Math.max(idx - 1, 0);
                            suggestionList.setSelectedIndex(prev);
                            suggestionList.ensureIndexIsVisible(prev);
                        }
                        e.consume();
                        break;
                    case KeyEvent.VK_ENTER:
                        if (idx >= 0) {
                            acceptSuggestion(suggestionModel.get(idx));
                        }
                        else {
                            suggestionPopup.setVisible(false);
                        }
                        e.consume();
                        break;
                    case KeyEvent.VK_ESCAPE:
                        suggestionPopup.setVisible(false);
                        e.consume();
                        break;
                    default:
                        break;
                }
            }
        });

        projectField.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) {
                suggestionPopup.setVisible(false);
            }
        });

        p.add(projectField, c);

        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        JButton importBtn = new JButton("Import job");
        importBtn.addActionListener(e -> runJobImport(importBtn));
        p.add(importBtn, c);

        c.gridy = 1;
        c.gridx = 0;
        c.weightx = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        p.add(new JLabel("PCB position:"), c);

        c.gridx = 1;
        c.gridwidth = 2;
        c.weightx = 1;
        p.add(pcbPositionLabel, c);

        return p;
    }

    private void refreshPcbPosition() {
        String text;
        try {
            Job job = MainFrame.get() != null && MainFrame.get().getJobTab() != null
                    ? MainFrame.get().getJobTab().getJob() : null;
            enforceDeferErrorHandling(job);
            if (job == null || job.getBoardLocations().isEmpty()) {
                text = "no job loaded";
            }
            else {
                StringBuilder sb = new StringBuilder();
                for (BoardLocation bl : job.getBoardLocations()) {
                    Location loc = bl.getGlobalLocation().convertToUnits(LengthUnit.Millimeters);
                    if (sb.length() > 0) {
                        sb.append("   |   ");
                    }
                    String name = bl.getBoard() != null ? bl.getBoard().getName() : "?";
                    boolean unset = loc.getX() == 0 && loc.getY() == 0;
                    sb.append(String.format(Locale.US,
                            "%s:  X %.3f  Y %.3f  Z %.3f  Rot %.1f°  (%s)%s",
                            name, loc.getX(), loc.getY(), loc.getZ(), loc.getRotation(),
                            bl.getGlobalSide(),
                            unset ? "  — not set, capture it in the Job tab" : ""));
                }
                text = sb.toString();
            }
        }
        catch (Exception ex) {
            text = "unavailable (" + ex.getMessage() + ")";
        }
        if (!text.equals(pcbPositionLabel.getText())) {
            pcbPositionLabel.setText(text);
        }
    }

    private JPanel buildActionsPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        p.setBorder(new TitledBorder("Actions"));

        JButton setupFpBtn = new JButton("Setup footprints");
        setupFpBtn.setToolTipText(
                "Create R/C packages (R0201..C1206) with footprints and JUKI nozzle assignments");
        setupFpBtn.addActionListener(e -> runSetupFootprints(setupFpBtn));
        p.add(setupFpBtn);

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

    private void runSetupFootprints(JButton trigger) {
        trigger.setEnabled(false);
        log("Setting up R/C footprint packages …");
        try {
            BaselineFootprints.SetupResult r = BaselineFootprints.setupAllPackages();
            Configuration.get().save();
            log(r.toString());
            for (String w : r.warnings) {
                log("  ! " + w);
            }
        }
        catch (Exception ex) {
            log("Setup failed: " + ex.getMessage());
        }
        finally {
            trigger.setEnabled(true);
        }
    }

    // ── Job import actions ──

    private void acceptSuggestion(ProjectOrderItem item) {
        selectedProjectOrder = item;
        suppressSearch = true;
        try {
            projectField.setText(item.toString());
        }
        finally {
            suppressSearch = false;
        }
        projectField.setCaretPosition(projectField.getText().length());
        suggestionPopup.setVisible(false);
    }

    private void showSuggestionPopup() {
        if (suggestionModel.getSize() == 0 || !projectField.isShowing()) {
            return;
        }
        java.awt.Dimension pref = suggestionPopup.getPreferredSize();
        suggestionPopup.setPopupSize(new java.awt.Dimension(
                Math.max(projectField.getWidth(), 200),
                Math.min(pref.height, 300)));
        if (!suggestionPopup.isVisible()) {
            suggestionPopup.show(projectField, 0, projectField.getHeight());
        }
    }

    private void runProjectSearch() {
        // Don't re-search the exact text of the suggestion the user just
        // picked; only free typing should trigger a search.
        String text = projectField.getText();
        if (selectedProjectOrder != null && text.equals(selectedProjectOrder.toString())) {
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
                    suggestionModel.clear();
                    for (ProjectOrderItem item : results) {
                        suggestionModel.addElement(item);
                    }
                    suggestionList.clearSelection();
                    // Only pop up while the user is actually in the field —
                    // the initial fetch on panel construction stays silent.
                    if (results.isEmpty()) {
                        suggestionPopup.setVisible(false);
                    }
                    else if (projectField.isFocusOwner()) {
                        showSuggestionPopup();
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
        HttpRequest request = ServerLinkConfig.authorize(HttpRequest.newBuilder(), url)
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
        ProjectOrderItem item = selectedProjectOrder;
        if (item != null) {
            projectOrderId = item.internalName != null && !item.internalName.isEmpty()
                    ? item.internalName : item.name;
        }
        else {
            projectOrderId = projectField.getText();
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
                            DeltaProtoJobImporter.buildJob(payload, defaultBoardLocation());
                    if (built.job == null) {
                        log("Job import failed: empty payload");
                        return;
                    }
                    applyPlacementRetries();
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

    /**
     * Make sure the currently loaded job uses ErrorHandling.Defer and the job
     * processor uses the configured retry attempts — no matter where the job
     * came from (DeltaProto import, File > Open, recent jobs). Without a
     * vacuum sensor this machine can only retry via Defer; a job left on the
     * default Alert stops the machine on the first failed pick/align.
     * Called from the 1-second UI refresh timer; only acts on change.
     */
    private void enforceDeferErrorHandling(Job job) {
        if (job != null && job.getErrorHandling() != Job.ErrorHandling.Defer) {
            job.setErrorHandling(Job.ErrorHandling.Defer);
            log("Job error handling set to Defer (failed placements are retried, job continues).");
        }
        int retries = placementRetryAttempts();
        org.openpnp.spi.PnpJobProcessor jp = Configuration.get().getMachine().getPnpJobProcessor();
        if (jp instanceof org.openpnp.machine.reference.ReferencePnpJobProcessor) {
            org.openpnp.machine.reference.ReferencePnpJobProcessor rjp =
                    (org.openpnp.machine.reference.ReferencePnpJobProcessor) jp;
            if (rjp.getMaxPlacementRetries() != retries) {
                rjp.setMaxPlacementRetries(retries);
                log("Placement retry attempts set to " + retries + ".");
            }
        }
    }

    /**
     * Push the configured retry attempts into the job processor's
     * maxPlacementRetries. Imported jobs use ErrorHandling.Defer, so a failed
     * pick/align re-queues the placement and it is retried up to this many
     * times before being marked errored (the job then continues with the
     * remaining placements instead of stopping the machine).
     */
    private void applyPlacementRetries() {
        int retries = placementRetryAttempts();
        org.openpnp.spi.PnpJobProcessor jp = Configuration.get().getMachine().getPnpJobProcessor();
        if (jp instanceof org.openpnp.machine.reference.ReferencePnpJobProcessor) {
            ((org.openpnp.machine.reference.ReferencePnpJobProcessor) jp)
                    .setMaxPlacementRetries(retries);
            log("Placement retry attempts set to " + retries + " (error handling: Defer).");
        }
        else {
            log("! Job processor is not ReferencePnpJobProcessor — retry setting not applied.");
        }
    }

    /** Scroll pane view that follows the viewport's width and only scrolls vertically. */
    private static class WidthTrackingPanel extends JPanel implements javax.swing.Scrollable {
        WidthTrackingPanel() {
            super(new BorderLayout());
        }

        @Override public java.awt.Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle r, int o, int d) {
            return 16;
        }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle r, int o, int d) {
            return Math.max(16, r.height - 16);
        }
        @Override public boolean getScrollableTracksViewportWidth() {
            return true;
        }
        @Override public boolean getScrollableTracksViewportHeight() {
            return false;
        }
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
