/*
 * Startup prompt asking the operator to connect and home the machine.
 *
 * Installed from DeltaProtoIntegration.install(). The dialog is queued with
 * SwingUtilities.invokeLater so it appears after the main window has been shown.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.openpnp.gui.MainFrame;
import org.openpnp.machine.hwgc.HwgcDriver;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Driver;
import org.openpnp.spi.Machine;
import org.openpnp.util.UiUtils;
import org.pmw.tinylog.Logger;

public final class StartupMachinePrompt {

    private static final String TITLE = "Connect and Home Machine";
    private static final String CONNECT_AND_HOME = "Connect & Home";
    private static final String NOT_NOW = "Not now";

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

        String message = "<html><body style='width: 340px'>"
                + "<b>The machine is not connected and homed yet.</b><br><br>"
                + "Connect to the machine and home all axes now?<br><br>"
                + "Make sure the work area is clear and the heads can move freely before homing."
                + "</body></html>";

        JLabel powerLabel = new JLabel(POWER_CHECKING);
        JButton connectButton = new JButton(CONNECT_AND_HOME);
        JButton notNowButton = new JButton(NOT_NOW);
        ControlPanelSchematic schematic = new ControlPanelSchematic();
        schematic.setVisible(false);
        JOptionPane pane = new JOptionPane(new Object[] {message, powerLabel, schematic},
                JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION, null,
                new Object[] {connectButton, notNowButton}, connectButton);
        connectButton.addActionListener(e -> pane.setValue(connectButton));
        notNowButton.addActionListener(e -> pane.setValue(notNowButton));
        JDialog dialog = pane.createDialog(mainFrame, TITLE);

        // Keep checking whether the machine is switched on while the dialog is open, so the
        // operator sees the state change as soon as the power / emergency stop is sorted.
        HwgcDriver driver = findHwgcDriver(machine);
        AtomicBoolean polling = new AtomicBoolean(driver != null);
        if (driver == null) {
            powerLabel.setVisible(false);
        }
        else {
            connectButton.setEnabled(false);
            Thread poller = new Thread(() -> {
                while (polling.get()) {
                    boolean powered = driver.isMachinePowered();
                    if (!polling.get()) {
                        break;
                    }
                    SwingUtilities.invokeLater(() -> {
                        if (connectButton.isEnabled() == powered && schematic.isVisible() != powered) {
                            return; // state unchanged — leave the dialog where the user put it
                        }
                        powerLabel.setText(powered ? POWER_ON : POWER_OFF);
                        connectButton.setEnabled(powered);
                        schematic.setVisible(!powered);
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

        dialog.setVisible(true);
        polling.set(false);
        dialog.dispose();

        if (pane.getValue() != connectButton) {
            Logger.info("Startup connect/home prompt dismissed by user");
            return;
        }

        connectAndHome(machine);
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
     * Enables (connects) the machine and homes it as a single machine task. The task is
     * submitted with ignoreEnabled=true because the machine is not enabled yet.
     */
    static void connectAndHome(Machine machine) {
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
            Logger.info("Startup prompt: machine connected and homed");
        }, (t) -> {
            UiUtils.showError(t);
        }, true);
    }
}
