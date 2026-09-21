/*
 * Startup prompt asking the operator to connect and home the machine.
 *
 * Installed from DeltaProtoIntegration.install(). The dialog is queued with
 * SwingUtilities.invokeLater so it appears after the main window has been shown.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.awt.BorderLayout;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import org.openpnp.gui.MainFrame;
import org.openpnp.machine.hwgc.HwgcDriver;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Driver;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;
import org.openpnp.util.UiUtils;
import org.pmw.tinylog.Logger;

public final class StartupMachinePrompt {

    private static final String TITLE = "Connect and Home Machine";
    private static final String CONNECT_AND_HOME = "Connect & Home";
    private static final String NOT_NOW = "Not now";

    private static final String MESSAGE = "<html><body style='width: 340px'>"
            + "<b>The machine is not connected and homed yet.</b><br><br>"
            + "Connect to the machine and home all axes now?<br><br>"
            + "Make sure the work area is clear and the heads can move freely before homing."
            + "</body></html>";
    private static final String MESSAGE_BUSY = "<html><body style='width: 340px'>"
            + "<b>Connecting and homing the machine…</b><br><br>"
            + "Keep your hands clear of the machine until homing is done. "
            + "This dialog closes automatically.</body></html>";

    private static final int POWER_POLL_INTERVAL_MS = 1500;
    private static final String POWER_CHECKING = "<html><body style='width: 340px'>"
            + "Checking whether the machine is switched on…</body></html>";
    private static final String POWER_ON = "<html><body style='width: 340px'>"
            + "<font color='#1b7f2a'><b>✔ Machine is ON and responding.</b></font>"
            + "</body></html>";
    private static final String POWER_OFF = "<html><body style='width: 340px'>"
            + "<font color='#c62828'><b>⚠ The machine is not ON.</b></font><br>"
            + "Turn on the machine, or twist the emergency stop button to release it. "
            + "This dialog updates automatically once the machine responds."
            + "</body></html>";

    private StartupMachinePrompt() {}

    /**
     * Queues the prompt so it is shown once the main window is visible.
     */
    public static void schedule(MainFrame mainFrame) {
        SwingUtilities.invokeLater(() -> {
            try {
                show(mainFrame);
            }
            catch (Throwable t) {
                Logger.error(t, "Failed to show startup connect/home prompt");
            }
        });
    }

    static void show(MainFrame mainFrame) {
        Machine machine = Configuration.get().getMachine();
        if (machine == null) {
            return;
        }
        if (machine.isEnabled() && machine.isHomed()) {
            // Nothing to do (e.g. machine auto-enabled and homed by other means).
            return;
        }

        JLabel messageLabel = new JLabel(MESSAGE);
        JLabel powerLabel = new JLabel(POWER_CHECKING);
        JButton connectButton = new JButton(CONNECT_AND_HOME);
        JButton notNowButton = new JButton(NOT_NOW);
        ControlPanelSchematic schematic = new ControlPanelSchematic();
        schematic.setVisible(false);
        HandsClearSchematic handsClear = new HandsClearSchematic();
        handsClear.setVisible(false);

        // Busy indicator shown in place of the power state while connecting and homing
        JLabel stageLabel = new JLabel(" ");
        JProgressBar progressBar = new JProgressBar();
        JPanel progressPanel = new JPanel(new BorderLayout(0, 4));
        progressPanel.add(stageLabel, BorderLayout.NORTH);
        progressPanel.add(progressBar, BorderLayout.CENTER);
        progressPanel.setVisible(false);

        JOptionPane pane = new JOptionPane(
                new Object[] {messageLabel, powerLabel, progressPanel, schematic, handsClear},
                JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION, null,
                new Object[] {connectButton, notNowButton}, connectButton);
        notNowButton.addActionListener(e -> pane.setValue(notNowButton));
        JDialog dialog = pane.createDialog(mainFrame, TITLE);

        // Keep checking whether the machine is switched on while the dialog is open, so the
        // operator sees the state change as soon as the power / emergency stop is sorted.
        HwgcDriver driver = findHwgcDriver(machine);
        AtomicBoolean polling = new AtomicBoolean(driver != null);
        if (driver == null) {
            powerLabel.setVisible(false);
            handsClear.setVisible(true);
            dialog.pack();
            dialog.setLocationRelativeTo(mainFrame);
        }
        else {
            connectButton.setEnabled(false);
            Thread poller = new Thread(() -> {
                while (polling.get()) {
                    boolean powered = driver.isMachinePowered();
                    SwingUtilities.invokeLater(() -> {
                        if (!polling.get()) {
                            return; // connecting / homing has taken over the dialog
                        }
                        if (connectButton.isEnabled() == powered
                                && schematic.isVisible() != powered) {
                            return; // state unchanged — leave the dialog where the user put it
                        }
                        powerLabel.setText(powered ? POWER_ON : POWER_OFF);
                        connectButton.setEnabled(powered);
                        schematic.setVisible(!powered);
                        handsClear.setVisible(powered);
                        dialog.pack();
                        dialog.setLocationRelativeTo(mainFrame);
                    });
                    try {
                        Thread.sleep(POWER_POLL_INTERVAL_MS);
                    }
                    catch (InterruptedException ie) {
                        break;
                    }
                }
            }, "hwgc-power-poll");
            poller.setDaemon(true);
            poller.start();
        }

        // Connect & Home keeps the dialog open: it turns into a progress display with the
        // hands-clear warning still in view, and closes by itself once homing is done.
        connectButton.addActionListener(e -> {
            polling.set(false);
            connectButton.setEnabled(false);
            notNowButton.setEnabled(false);
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            messageLabel.setText(MESSAGE_BUSY);
            powerLabel.setVisible(false);
            schematic.setVisible(false);
            handsClear.setVisible(true);
            stageLabel.setText("Connecting to the machine…");
            progressBar.setIndeterminate(true);
            progressPanel.setVisible(true);
            dialog.pack();
            dialog.setLocationRelativeTo(mainFrame);

            // The firmware reports no homing progress, so only the stage is shown.
            MachineListener enabledListener = new MachineListener.Adapter() {
                @Override
                public void machineEnabled(Machine m) {
                    SwingUtilities.invokeLater(() -> stageLabel.setText("Homing…"));
                }
            };
            machine.addListener(enabledListener);
            if (machine.isEnabled()) {
                stageLabel.setText("Homing…");
            }
            connectAndHome(machine, () -> {
                machine.removeListener(enabledListener);
                Logger.info("Startup prompt: machine connected and homed");
                pane.setValue(connectButton);
            }, (t) -> {
                machine.removeListener(enabledListener);
                pane.setValue(notNowButton);
                UiUtils.showError(t);
            });
        });

        dialog.setVisible(true);
        polling.set(false);
        dialog.dispose();

        if (pane.getValue() != connectButton) {
            Logger.info("Startup connect/home prompt closed without homing");
        }
    }

    private static HwgcDriver findHwgcDriver(Machine machine) {
        for (Driver driver : machine.getDrivers()) {
            if (driver instanceof HwgcDriver) {
                return (HwgcDriver) driver;
            }
        }
        return null;
    }

    /**
     * Enables (connects) the machine and homes it as a machine task. The task is submitted
     * with ignoreEnabled=true because the machine is not enabled yet. The callbacks run on
     * the Swing event thread.
     */
    static void connectAndHome(Machine machine, Runnable onDone, Consumer<Throwable> onFailure) {
        UiUtils.submitUiMachineTask(() -> {
            boolean enabledNow = false;
            if (!machine.isEnabled()) {
                Logger.info("Startup prompt: connecting machine");
                machine.setEnabled(true);
                enabledNow = true;
            }
            // ReferenceMachine already submits a home() when "home after enabled" is set,
            // so only home explicitly when that did not just happen.
            boolean homeAfterEnabled = machine instanceof ReferenceMachine
                    && ((ReferenceMachine) machine).getHomeAfterEnabled();
            if (!(enabledNow && homeAfterEnabled)) {
                Logger.info("Startup prompt: homing machine");
                machine.home();
            }
            return null;
        }, (result) -> {
            if (machine.isHomed()) {
                onDone.run();
            }
            else {
                // The "home after enabled" task is still queued behind ours: machine tasks
                // run one at a time, so an empty task behind it completes when homing is done.
                UiUtils.submitUiMachineTask(() -> null, (r) -> onDone.run(), onFailure, true);
            }
        }, onFailure, true);
    }
}
