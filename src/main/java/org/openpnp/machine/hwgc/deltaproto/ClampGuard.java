/*
 * Asks the operator to clamp the PCB before anything that relies on the board
 * lying still: aligning the PCB (fiducial check, two-point locate) and
 * starting, resuming or stepping a job.
 *
 * JobPanel is upstream code, so instead of editing it the guard swaps the
 * public Action objects on the buttons and menu items that use them for a
 * wrapper that asks first and then delegates to the original action.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.beans.PropertyChangeListener;

import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JMenu;
import javax.swing.JOptionPane;

import org.openpnp.gui.JobPanel;
import org.openpnp.gui.MainFrame;
import org.openpnp.machine.hwgc.HwgcDriver;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Driver;
import org.pmw.tinylog.Logger;

final class ClampGuard {

    private static final String CLAMP_AND_CONTINUE = "Clamp & continue";
    private static final String CONTINUE_UNCLAMPED = "Continue without clamping";
    private static final String CANCEL = "Cancel";
    /** Time for the clamp solenoid to settle before the guarded action moves anything. */
    private static final int CLAMP_SETTLE_MS = 500;

    private ClampGuard() {}

    static void install(MainFrame mainFrame) {
        JobPanel jobPanel = mainFrame.getJobTab();
        if (jobPanel == null) {
            return;
        }
        // Start doubles as Pause: only ask when it is about to start or resume.
        int n = guard(mainFrame, jobPanel.startPauseResumeJobAction, "start the job",
                () -> !"Running".equals(JobStateProbe.jobPanelState())
                        && !"Pausing".equals(JobStateProbe.jobPanelState()));
        n += guard(mainFrame, jobPanel.stepJobAction, "step the job", () -> true);
        n += guard(mainFrame, jobPanel.fiducialCheckAction, "align the PCB", () -> true);
        n += guard(mainFrame, jobPanel.twoPointLocateBoardLocationAction, "align the PCB",
                () -> true);
        Logger.info("DeltaProto clamp guard installed on {} button(s)/menu item(s)", n);
    }

    private interface Condition {
        boolean applies();
    }

    private static int guard(MainFrame mainFrame, Action original, String what,
            Condition condition) {
        Action wrapper = new GuardedAction(original, what, condition);
        int n = replace(mainFrame.getContentPane(), original, wrapper);
        if (mainFrame.getJMenuBar() != null) {
            n += replace(mainFrame.getJMenuBar(), original, wrapper);
        }
        return n;
    }

    private static int replace(Component c, Action original, Action wrapper) {
        int n = 0;
        if (c instanceof AbstractButton && ((AbstractButton) c).getAction() == original) {
            ((AbstractButton) c).setAction(wrapper);
            n++;
        }
        if (c instanceof JMenu) {
            for (Component child : ((JMenu) c).getMenuComponents()) {
                n += replace(child, original, wrapper);
            }
        }
        else if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                n += replace(child, original, wrapper);
            }
        }
        return n;
    }

    /** @return true when the guarded action may go ahead. */
    static boolean confirmClamped(Component parent, String what) {
        HwgcDriver driver = findDriver();
        // Not connected: nothing can move and nothing can be clamped; the
        // action itself reports that the machine is not started.
        if (driver == null || !driver.isConnected() || driver.isBoardClamped()) {
            return true;
        }
        String message = "<html><body style='width: 340px'>"
                + "<b>The PCB is not clamped.</b><br><br>"
                + "An unclamped board can shift while the machine works on it. "
                + "Clamp it before you " + what + "?</body></html>";
        Object[] options = {CLAMP_AND_CONTINUE, CONTINUE_UNCLAMPED, CANCEL};
        int choice = JOptionPane.showOptionDialog(parent, message, "PCB not clamped",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options,
                CLAMP_AND_CONTINUE);
        if (choice == 0) {
            try {
                driver.sendExecutePlywood(0, true);
                Thread.sleep(CLAMP_SETTLE_MS);
                return true;
            }
            catch (Exception e) {
                JOptionPane.showMessageDialog(parent, "Clamping failed: " + e.getMessage(),
                        "PCB not clamped", JOptionPane.ERROR_MESSAGE);
                return false;
            }
        }
        return choice == 1;
    }

    private static HwgcDriver findDriver() {
        try {
            for (Driver d : Configuration.get().getMachine().getDrivers()) {
                if (d instanceof HwgcDriver) {
                    return (HwgcDriver) d;
                }
            }
        }
        catch (Throwable t) {
            // no machine yet
        }
        return null;
    }

    /** Looks and enables exactly like the original action, but asks about the clamp first. */
    private static final class GuardedAction extends AbstractAction {
        private final Action original;
        private final String what;
        private final Condition condition;

        GuardedAction(Action original, String what, Condition condition) {
            this.original = original;
            this.what = what;
            this.condition = condition;
            if (original instanceof AbstractAction) {
                Object[] keys = ((AbstractAction) original).getKeys();
                if (keys != null) {
                    for (Object key : keys) {
                        putValue((String) key, original.getValue((String) key));
                    }
                }
            }
            setEnabled(original.isEnabled());
            // JobPanel renames Start → Pause → Resume and toggles enabled on
            // the original; mirror every change onto the wrapper.
            PropertyChangeListener mirror = e -> {
                if ("enabled".equals(e.getPropertyName())) {
                    setEnabled(original.isEnabled());
                }
                else {
                    putValue(e.getPropertyName(), e.getNewValue());
                }
            };
            original.addPropertyChangeListener(mirror);
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            Component parent = e.getSource() instanceof Component
                    ? (Component) e.getSource() : MainFrame.get();
            if (condition.applies() && !confirmClamped(MainFrame.get() != null
                    ? MainFrame.get() : parent, what)) {
                return;
            }
            original.actionPerformed(e);
        }
    }
}
