/*
 * Startup prompt asking the operator to connect and home the machine.
 *
 * Installed from DeltaProtoIntegration.install(). The dialog is queued with
 * SwingUtilities.invokeLater so it appears after the main window has been shown.
 */
package org.openpnp.machine.hwgc.deltaproto;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.openpnp.gui.MainFrame;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Machine;
import org.openpnp.util.UiUtils;
import org.pmw.tinylog.Logger;

public final class StartupMachinePrompt {

    private static final String TITLE = "Connect and Home Machine";
    private static final String CONNECT_AND_HOME = "Connect & Home";
    private static final String NOT_NOW = "Not now";

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
        Object[] options = {CONNECT_AND_HOME, NOT_NOW};
        int choice = JOptionPane.showOptionDialog(mainFrame, message, TITLE,
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice != 0) {
            Logger.info("Startup connect/home prompt dismissed by user");
            return;
        }

        connectAndHome(machine);
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
