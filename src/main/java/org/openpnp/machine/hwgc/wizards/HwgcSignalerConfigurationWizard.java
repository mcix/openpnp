/*
 * Copyright (C) 2026 mcix
 *
 * This file is part of OpenPnP.
 *
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 *
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.machine.hwgc.wizards;

import java.awt.Color;
import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.TitledBorder;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;

import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.gui.support.IntegerConverter;
import org.openpnp.machine.hwgc.HwgcSignaler;
import org.openpnp.spi.base.AbstractJobProcessor.State;

@SuppressWarnings("serial")
public class HwgcSignalerConfigurationWizard extends AbstractConfigurationWizard {
    private final HwgcSignaler signaler;

    private JTextField beepCountField;
    private JTextField beepOnMsField;
    private JTextField beepOffMsField;
    private JTextField flashIntervalMsField;
    private JTextField mouseArmDelayMsField;

    public HwgcSignalerConfigurationWizard(HwgcSignaler signaler) {
        this.signaler = signaler;

        JPanel panel = new JPanel();
        panel.setBorder(new TitledBorder(null,
                "Stack Light / Buzzer Signaling", TitledBorder.LEADING, TitledBorder.TOP,
                null, new Color(0, 0, 0)));
        contentPanel.add(panel);
        panel.setLayout(new FormLayout(
                new ColumnSpec[] {
                    FormSpecs.RELATED_GAP_COLSPEC,
                    FormSpecs.DEFAULT_COLSPEC,
                    FormSpecs.RELATED_GAP_COLSPEC,
                    FormSpecs.DEFAULT_COLSPEC,
                    FormSpecs.RELATED_GAP_COLSPEC, },
                new RowSpec[] {
                    FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                    FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                    FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                    FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                    FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                    FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                    FormSpecs.RELATED_GAP_ROWSPEC, }));

        int row = 2;

        panel.add(new JLabel("Beep count (job finished)"), "2, " + row + ", right, default");
        beepCountField = new JTextField(10);
        panel.add(beepCountField, "4, " + row + ", fill, default");
        row += 2;

        panel.add(new JLabel("Beep on time (ms)"), "2, " + row + ", right, default");
        beepOnMsField = new JTextField(10);
        panel.add(beepOnMsField, "4, " + row + ", fill, default");
        row += 2;

        panel.add(new JLabel("Beep off time (ms)"), "2, " + row + ", right, default");
        beepOffMsField = new JTextField(10);
        panel.add(beepOffMsField, "4, " + row + ", fill, default");
        row += 2;

        panel.add(new JLabel("Yellow flash interval (ms)"), "2, " + row + ", right, default");
        flashIntervalMsField = new JTextField(10);
        panel.add(flashIntervalMsField, "4, " + row + ", fill, default");
        row += 2;

        panel.add(new JLabel("Mouse-clear grace period (ms)"), "2, " + row + ", right, default");
        mouseArmDelayMsField = new JTextField(10);
        panel.add(mouseArmDelayMsField, "4, " + row + ", fill, default");
        row += 2;

        JButton testError = new JButton(testErrorAction);
        panel.add(testError, "2, " + row);
        JButton testFinished = new JButton(testFinishedAction);
        panel.add(testFinished, "4, " + row);
    }

    private final Action testErrorAction = new AbstractAction("Test Job Error") {
        @Override
        public void actionPerformed(ActionEvent e) {
            signaler.test(State.ERROR);
        }
    };

    private final Action testFinishedAction = new AbstractAction("Test Job Finished") {
        @Override
        public void actionPerformed(ActionEvent e) {
            signaler.test(State.FINISHED);
        }
    };

    @Override
    public void createBindings() {
        IntegerConverter intConverter = new IntegerConverter();

        addWrappedBinding(signaler, "beepCount", beepCountField, "text", intConverter);
        addWrappedBinding(signaler, "beepOnMs", beepOnMsField, "text", intConverter);
        addWrappedBinding(signaler, "beepOffMs", beepOffMsField, "text", intConverter);
        addWrappedBinding(signaler, "flashIntervalMs", flashIntervalMsField, "text", intConverter);
        addWrappedBinding(signaler, "mouseArmDelayMs", mouseArmDelayMsField, "text", intConverter);
    }
}
