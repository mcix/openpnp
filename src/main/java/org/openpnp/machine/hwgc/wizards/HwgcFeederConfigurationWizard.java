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
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.TitledBorder;

import org.jdesktop.beansbinding.AutoBinding.UpdateStrategy;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.components.ComponentDecorators;
import org.openpnp.gui.components.LocationButtonsPanel;
import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.gui.support.DoubleConverter;
import org.openpnp.gui.support.IntegerConverter;
import org.openpnp.gui.support.LengthConverter;
import org.openpnp.gui.support.MutableLocationProxy;
import org.openpnp.machine.hwgc.HwgcFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Camera;
import org.openpnp.util.UiUtils;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.ui.CvPipelineEditor;
import org.openpnp.vision.pipeline.ui.CvPipelineEditorDialog;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;

@SuppressWarnings("serial")
public class HwgcFeederConfigurationWizard extends AbstractConfigurationWizard {
    private final HwgcFeeder feeder;

    private JTextField feederNumberField;
    private JTextField feedDurationField;

    private JTextField pickX;
    private JTextField pickY;
    private JTextField pickZ;
    private LocationButtonsPanel pickButtons;

    private JTextField offsetX;
    private JTextField offsetY;

    private JTextField holeDiameterField;
    private JTextField holeSearchField;

    private JTextField tapeWidthField;
    private JTextField diameterToleranceField;
    private JTextField pitchToleranceField;

    public HwgcFeederConfigurationWizard(HwgcFeeder feeder) {
        this.feeder = feeder;

        contentPanel.add(buildSettingsPanel());
        contentPanel.add(buildLocationPanel());
        contentPanel.add(buildVisionPanel());
        contentPanel.add(buildTapeCalibrationPanel());
    }

    private JPanel buildSettingsPanel() {
        JPanel panel = new JPanel();
        panel.setBorder(new TitledBorder(null,
                "HWGC Feeder", TitledBorder.LEADING, TitledBorder.TOP,
                null, new Color(0, 0, 0)));
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
                        FormSpecs.RELATED_GAP_ROWSPEC, }));

        panel.add(new JLabel("Slot Number (1-50)"), "2, 2, right, default");
        feederNumberField = new JTextField(10);
        panel.add(feederNumberField, "4, 2, fill, default");

        panel.add(new JLabel("Feed Duration (ms)"), "2, 4, right, default");
        feedDurationField = new JTextField(10);
        panel.add(feedDurationField, "4, 4, fill, default");

        return panel;
    }

    private JPanel buildLocationPanel() {
        JPanel panel = new JPanel();
        panel.setBorder(new TitledBorder(null,
                "Pick Location", TitledBorder.LEADING, TitledBorder.TOP,
                null, new Color(0, 0, 0)));
        panel.setLayout(new FormLayout(
                new ColumnSpec[] {
                        FormSpecs.RELATED_GAP_COLSPEC,
                        FormSpecs.DEFAULT_COLSPEC,
                        FormSpecs.RELATED_GAP_COLSPEC,
                        FormSpecs.DEFAULT_COLSPEC,
                        FormSpecs.RELATED_GAP_COLSPEC,
                        FormSpecs.DEFAULT_COLSPEC,
                        FormSpecs.RELATED_GAP_COLSPEC,
                        FormSpecs.DEFAULT_COLSPEC,
                        FormSpecs.RELATED_GAP_COLSPEC,
                        FormSpecs.DEFAULT_COLSPEC,
                        FormSpecs.RELATED_GAP_COLSPEC, },
                new RowSpec[] {
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, }));

        panel.add(new JLabel("X / Y / Z"), "2, 2, right, default");
        pickX = new JTextField(8);
        panel.add(pickX, "4, 2, fill, default");
        pickY = new JTextField(8);
        panel.add(pickY, "6, 2, fill, default");
        pickZ = new JTextField(8);
        panel.add(pickZ, "8, 2, fill, default");

        pickButtons = new LocationButtonsPanel(pickX, pickY, pickZ, null);
        panel.add(pickButtons, "10, 2");

        JButton openBtn = new JButton(openFeederAction);
        panel.add(openBtn, "4, 4");
        JButton closeBtn = new JButton(closeFeederAction);
        panel.add(closeBtn, "6, 4");

        return panel;
    }

    private JPanel buildVisionPanel() {
        JPanel panel = new JPanel();
        panel.setBorder(new TitledBorder(null,
                "Vision Calibration", TitledBorder.LEADING, TitledBorder.TOP,
                null, new Color(0, 0, 0)));
        panel.setLayout(new FormLayout(
                new ColumnSpec[] {
                        FormSpecs.RELATED_GAP_COLSPEC,
                        FormSpecs.DEFAULT_COLSPEC,
                        FormSpecs.RELATED_GAP_COLSPEC,
                        FormSpecs.DEFAULT_COLSPEC,
                        FormSpecs.RELATED_GAP_COLSPEC,
                        FormSpecs.DEFAULT_COLSPEC,
                        FormSpecs.RELATED_GAP_COLSPEC, },
                new RowSpec[] {
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, }));

        panel.add(new JLabel("Hole \u2192 Part Offset X / Y"), "2, 2, right, default");
        offsetX = new JTextField(8);
        panel.add(offsetX, "4, 2, fill, default");
        offsetY = new JTextField(8);
        panel.add(offsetY, "6, 2, fill, default");

        panel.add(new JLabel("Hole Diameter / Search"), "2, 4, right, default");
        holeDiameterField = new JTextField(8);
        panel.add(holeDiameterField, "4, 4, fill, default");
        holeSearchField = new JTextField(8);
        panel.add(holeSearchField, "6, 4, fill, default");

        JButton captureBtn = new JButton(captureFromCameraAction);
        panel.add(captureBtn, "2, 6");
        JButton calibrateBtn = new JButton(calibrateFromCameraAction);
        panel.add(calibrateBtn, "4, 6");

        return panel;
    }

    private JPanel buildTapeCalibrationPanel() {
        JPanel panel = new JPanel();
        panel.setBorder(new TitledBorder(null,
                "EIA-481 Tape Calibration", TitledBorder.LEADING, TitledBorder.TOP,
                null, new Color(0, 0, 0)));
        panel.setLayout(new FormLayout(
                new ColumnSpec[] {
                        FormSpecs.RELATED_GAP_COLSPEC,
                        FormSpecs.DEFAULT_COLSPEC,
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
                        FormSpecs.RELATED_GAP_ROWSPEC, }));

        panel.add(new JLabel("Tape Width"), "2, 2, right, default");
        tapeWidthField = new JTextField(8);
        panel.add(tapeWidthField, "4, 2, fill, default");
        panel.add(new JLabel("(8, 12, 16, 24 mm)"), "6, 2");

        panel.add(new JLabel("Diameter Tolerance"), "2, 4, right, default");
        diameterToleranceField = new JTextField(8);
        panel.add(diameterToleranceField, "4, 4, fill, default");
        panel.add(new JLabel("(0.0 – 1.0, e.g. 0.5 = \u00b150%)"), "6, 4");

        panel.add(new JLabel("Pitch Tolerance"), "2, 6, right, default");
        pitchToleranceField = new JTextField(8);
        panel.add(pitchToleranceField, "4, 6, fill, default");
        panel.add(new JLabel("(holes must be N\u00d74mm \u00b1 this)"), "6, 6");

        JButton calibrateBtn = new JButton(calibrateFromTapeAction);
        panel.add(calibrateBtn, "2, 8");

        JButton editPipelineBtn = new JButton(editPipelineAction);
        panel.add(editPipelineBtn, "4, 8");
        JButton resetPipelineBtn = new JButton(resetPipelineAction);
        panel.add(resetPipelineBtn, "6, 8");

        return panel;
    }

    @Override
    public void createBindings() {
        IntegerConverter intConverter = new IntegerConverter();
        LengthConverter lengthConverter = new LengthConverter();

        addWrappedBinding(feeder, "feederNumber", feederNumberField, "text", intConverter);
        addWrappedBinding(feeder, "feedDurationMs", feedDurationField, "text", intConverter);

        MutableLocationProxy pickLocation = new MutableLocationProxy();
        bind(UpdateStrategy.READ_WRITE, feeder, "location", pickLocation, "location");
        addWrappedBinding(pickLocation, "lengthX", pickX, "text", lengthConverter);
        addWrappedBinding(pickLocation, "lengthY", pickY, "text", lengthConverter);
        addWrappedBinding(pickLocation, "lengthZ", pickZ, "text", lengthConverter);

        MutableLocationProxy offsetLocation = new MutableLocationProxy();
        bind(UpdateStrategy.READ_WRITE, feeder, "holeToPartOffset", offsetLocation, "location");
        addWrappedBinding(offsetLocation, "lengthX", offsetX, "text", lengthConverter);
        addWrappedBinding(offsetLocation, "lengthY", offsetY, "text", lengthConverter);

        addWrappedBinding(feeder, "holeDiameter", holeDiameterField, "text", lengthConverter);
        addWrappedBinding(feeder, "holeSearchDistance", holeSearchField, "text", lengthConverter);
        addWrappedBinding(feeder, "tapeWidth", tapeWidthField, "text", lengthConverter);
        addWrappedBinding(feeder, "holeDiameterTolerance", diameterToleranceField, "text",
                new DoubleConverter(Configuration.get().getLengthDisplayFormat()));
        addWrappedBinding(feeder, "holePitchTolerance", pitchToleranceField, "text", lengthConverter);

        ComponentDecorators.decorateWithAutoSelect(feederNumberField);
        ComponentDecorators.decorateWithAutoSelect(feedDurationField);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(pickX);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(pickY);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(pickZ);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(offsetX);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(offsetY);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(holeDiameterField);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(holeSearchField);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(tapeWidthField);
        ComponentDecorators.decorateWithAutoSelect(diameterToleranceField);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(pitchToleranceField);
    }

    private final Action openFeederAction = new AbstractAction("Open") {
        @Override
        public void actionPerformed(ActionEvent e) {
            applyAction.actionPerformed(e);
            UiUtils.submitUiMachineTask(() -> feeder.setOpen(true));
        }
    };

    private final Action closeFeederAction = new AbstractAction("Close") {
        @Override
        public void actionPerformed(ActionEvent e) {
            applyAction.actionPerformed(e);
            UiUtils.submitUiMachineTask(() -> feeder.setOpen(false));
        }
    };

    private final Action captureFromCameraAction = new AbstractAction("Capture from Camera Position") {
        @Override
        public void actionPerformed(ActionEvent e) {
            applyAction.actionPerformed(e);
            UiUtils.submitUiMachineTask(() -> {
                Camera camera = MainFrame.get().getMachineControls().getSelectedTool()
                        .getHead().getDefaultCamera();
                feeder.captureFromCurrentPosition(camera);
            });
        }
    };

    private final Action calibrateFromCameraAction = new AbstractAction("Calibrate from Camera") {
        @Override
        public void actionPerformed(ActionEvent e) {
            applyAction.actionPerformed(e);
            UiUtils.submitUiMachineTask(() -> {
                Camera camera = MainFrame.get().getMachineControls().getSelectedTool()
                        .getHead().getDefaultCamera();
                feeder.calibrateLocation(camera);
            });
        }
    };

    private final Action calibrateFromTapeAction = new AbstractAction("Calibrate from Tape") {
        @Override
        public void actionPerformed(ActionEvent e) {
            applyAction.actionPerformed(e);
            UiUtils.submitUiMachineTask(() -> {
                Camera camera = MainFrame.get().getMachineControls().getSelectedTool()
                        .getHead().getDefaultCamera();
                feeder.calibrateFromTape(camera);
            });
        }
    };

    private final Action editPipelineAction = new AbstractAction("Edit Pipeline") {
        @Override
        public void actionPerformed(ActionEvent e) {
            UiUtils.messageBoxOnException(() -> {
                Camera camera = Configuration.get().getMachine()
                        .getDefaultHead().getDefaultCamera();
                CvPipeline pipeline = HwgcFeeder.getPipeline();
                pipeline.setProperty("camera", camera);
                pipeline.setProperty("feeder", feeder);
                CvPipelineEditor editor = new CvPipelineEditor(pipeline);
                JDialog dialog = new CvPipelineEditorDialog(
                        MainFrame.get(), "HwgcFeeder Shared Pipeline", editor);
                dialog.setVisible(true);
            });
        }
    };

    private final Action resetPipelineAction = new AbstractAction("Reset Pipeline") {
        @Override
        public void actionPerformed(ActionEvent e) {
            HwgcFeeder.resetPipeline();
        }
    };
}
