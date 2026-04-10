/*
 * One-call installer for DeltaProto UI integration.
 *
 * The only upstream OpenPNP file that needs to reference DeltaProto code is
 * MainFrame.java, via a single line:
 *
 *     DeltaProtoIntegration.install(this);
 *
 * placed at the end of the MainFrame constructor. That keeps the DeltaProto
 * patch against upstream to a one-line delta that is trivial to rebase.
 */
package org.openpnp.machine.hwgc.deltaproto;

import javax.swing.JTabbedPane;

import org.openpnp.gui.MainFrame;
import org.pmw.tinylog.Logger;

public final class DeltaProtoIntegration {

    private static final String TAB_TITLE = "DeltaProto";

    private DeltaProtoIntegration() {}

    /**
     * Adds the DeltaProto control tab to the given {@link MainFrame}. Safe to
     * call multiple times — subsequent calls are no-ops. Any failure is logged
     * and swallowed so a broken DeltaProto integration cannot prevent OpenPNP
     * from starting.
     */
    public static void install(MainFrame mainFrame) {
        try {
            JTabbedPane tabs = mainFrame.getTabs();
            for (int i = 0; i < tabs.getTabCount(); i++) {
                if (TAB_TITLE.equals(tabs.getTitleAt(i))) {
                    return;
                }
            }
            tabs.addTab(TAB_TITLE, new DeltaProtoPanel());
            Logger.info("DeltaProto tab installed");
        }
        catch (Throwable t) {
            Logger.error(t, "Failed to install DeltaProto tab");
        }
    }
}
