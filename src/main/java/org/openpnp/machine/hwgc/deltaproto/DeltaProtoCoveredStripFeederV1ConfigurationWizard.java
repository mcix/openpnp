/*
 * Configuration wizard for {@link DeltaProtoCoveredStripFeederV1}.
 *
 * Lives in the isolated org.openpnp.machine.hwgc.deltaproto subpackage so
 * upstream merges never touch DeltaProto code.
 */
package org.openpnp.machine.hwgc.deltaproto;

import java.awt.Color;
import java.awt.event.ActionEvent;
import java.util.Locale;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComboBox;
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
import org.openpnp.gui.support.IdentifiableListCellRenderer;
import org.openpnp.gui.support.IntegerConverter;
import org.openpnp.gui.support.LengthConverter;
import org.openpnp.gui.support.MutableLocationProxy;
import org.openpnp.gui.support.PartsComboBoxModel;
import org.openpnp.model.Configuration;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.Camera;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.Nozzle;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.UiUtils;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;

@SuppressWarnings("serial")
public class DeltaProtoCoveredStripFeederV1ConfigurationWizard extends AbstractConfigurationWizard {
    private final DeltaProtoCoveredStripFeederV1 feeder;

    private JComboBox<Part> partCombo;
    private JTextField pinField;
    private JComboBox<Double> pitchCombo;
    private JTextField feedPositionField;
    private JTextField coverSlideSpeedField;

    private JTextField coverStartXField;
    private JTextField coverStartYField;
    private JTextField coverStartZField;
    private JTextField firstPickXField;
    private JTextField firstPickYField;
    private JTextField firstPickZField;

    private final JLabel originLocationLabel = new JLabel(" ");
    private final JLabel nextPickupLabel = new JLabel(" ");

    public DeltaProtoCoveredStripFeederV1ConfigurationWizard(DeltaProtoCoveredStripFeederV1 feeder) {
        this.feeder = feeder;
        contentPanel.add(buildStripPanel());
        contentPanel.add(buildLocationsPanel());
        contentPanel.add(buildManualStepsPanel());
        refreshReadouts();
    }

    private JPanel buildStripPanel() {
        JPanel panel = new JPanel();
        panel.setBorder(new TitledBorder(null,
                "Covered Plankje Strip (cover slid open by the picking nozzle)", TitledBorder.LEADING,
                TitledBorder.TOP, null, new Color(0, 0, 0)));
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
                        FormSpecs.RELATED_GAP_COLSPEC, },
                new RowSpec[] {
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, }));

        panel.add(new JLabel("Part"), "2, 2, right, default");
        partCombo = new JComboBox<>();
        partCombo.setMaximumRowCount(20);
        partCombo.setModel(new PartsComboBoxModel());
        partCombo.setRenderer(new IdentifiableListCellRenderer<Part>());
        panel.add(partCombo, "4, 2, 5, 1, left, default");

        panel.add(new JLabel("Pin (" + PlankjeLayout.PIN_FIRST + "-"
                + PlankjeLayout.PIN_LAST + ")"), "2, 4, right, default");
        pinField = new JTextField(6);
        panel.add(pinField, "4, 4, fill, default");

        panel.add(new JLabel("Pitch (mm)"), "6, 4, right, default");
        pitchCombo = new JComboBox<>(new Double[] {4.0, 2.0});
        panel.add(pitchCombo, "8, 4, fill, default");

        panel.add(new JLabel("Feed position (0 = first)"), "2, 6, right, default");
        feedPositionField = new JTextField(6);
        panel.add(feedPositionField, "4, 6, fill, default");
        JButton resetBtn = new JButton(resetFeedPositionAction);
        panel.add(resetBtn, "6, 6");
        JButton backOneBtn = new JButton(backOneAction);
        panel.add(backOneBtn, "8, 6");

        panel.add(new JLabel("Cover slide speed (0-1)"), "2, 8, right, default");
        coverSlideSpeedField = new JTextField(6);
        panel.add(coverSlideSpeedField, "4, 8, fill, default");

        panel.add(new JLabel("Origin (pin/hole):"), "2, 10, right, default");
        panel.add(originLocationLabel, "4, 10, 5, 1");

        return panel;
    }

    /**
     * OpenPnP-style location rows: X/Y/Z fields plus the standard
     * position-camera / position-tool / capture-camera / capture-tool
     * buttons. X/Y write back as offsets from the pin; Z writes back to the
     * plankje-wide cover Z / pick Z.
     */
    private JPanel buildLocationsPanel() {
        JPanel panel = new JPanel();
        panel.setBorder(new TitledBorder(null,
                "Locations (X/Y stored as offsets from pin, Z shared for the whole plankje)",
                TitledBorder.LEADING, TitledBorder.TOP, null, new Color(0, 0, 0)));
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
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, }));

        // Header row
        panel.add(new JLabel("X"), "4, 2, center, default");
        panel.add(new JLabel("Y"), "6, 2, center, default");
        panel.add(new JLabel("Z"), "8, 2, center, default");

        panel.add(new JLabel("Cover plunge"), "2, 4, right, default");
        coverStartXField = new JTextField(8);
        panel.add(coverStartXField, "4, 4, fill, default");
        coverStartYField = new JTextField(8);
        panel.add(coverStartYField, "6, 4, fill, default");
        coverStartZField = new JTextField(8);
        panel.add(coverStartZField, "8, 4, fill, default");
        LocationButtonsPanel coverStartButtons = new LocationButtonsPanel(
                coverStartXField, coverStartYField, coverStartZField, null);
        // The cover slide is done by whichever nozzle picks the part, so the
        // standard tool buttons (selected nozzle) are the right behavior.
        panel.add(coverStartButtons, "10, 4");

        panel.add(new JLabel("First pickup (index 0)"), "2, 6, right, default");
        firstPickXField = new JTextField(8);
        panel.add(firstPickXField, "4, 6, fill, default");
        firstPickYField = new JTextField(8);
        panel.add(firstPickYField, "6, 6, fill, default");
        firstPickZField = new JTextField(8);
        panel.add(firstPickZField, "8, 6, fill, default");
        LocationButtonsPanel firstPickButtons = new LocationButtonsPanel(
                firstPickXField, firstPickYField, firstPickZField, null);
        panel.add(firstPickButtons, "10, 6");

        panel.add(new JLabel("Next pickup:"), "2, 8, right, default");
        panel.add(nextPickupLabel, "4, 8, 7, 1");

        return panel;
    }

    /**
     * Manual, individually guarded steps of the feed cycle, so the operation
     * can be verified without ever moving to a wrong coordinate: each step
     * refuses to run unless the nozzle is where the previous step left it.
     * The steps use the currently selected nozzle (machine controls), same
     * as a real feed uses the nozzle that will pick the part.
     */
    private JPanel buildManualStepsPanel() {
        JPanel panel = new JPanel();
        panel.setBorder(new TitledBorder(null,
                "Manual step-by-step verification (selected nozzle)", TitledBorder.LEADING,
                TitledBorder.TOP, null, new Color(0, 0, 0)));
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
                        FormSpecs.RELATED_GAP_COLSPEC, },
                new RowSpec[] {
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, }));

        panel.add(new JButton(step1Action), "2, 2");
        panel.add(new JButton(step2Action), "4, 2");
        panel.add(new JButton(step3Action), "6, 2");
        panel.add(new JButton(step4Action), "8, 2");

        panel.add(new JButton(fullCycleAction), "2, 4");
        panel.add(new JButton(moveCameraToOriginAction), "4, 4");
        panel.add(new JButton(moveCameraToPickupAction), "6, 4");
        panel.add(new JButton(nozzleAbovePickupAction), "8, 4");

        panel.add(new JLabel("Steps 2 and 3 refuse to run unless the nozzle is exactly "
                + "where the previous step left it."), "2, 6, 7, 1");

        return panel;
    }

    private void refreshReadouts() {
        try {
            Location origin = feeder.getOriginLocation();
            originLocationLabel.setText(String.format(Locale.US,
                    "X %.3f  Y %.3f  Z %.3f",
                    origin.getX(), origin.getY(), origin.getZ()));
            Location next = feeder.getNextPickLocation();
            nextPickupLabel.setText(String.format(Locale.US,
                    "X %.3f  Y %.3f  Z %.3f  (index %d)",
                    next.getX(), next.getY(), next.getZ(), feeder.getFeedPosition()));
        }
        catch (Exception e) {
            nextPickupLabel.setText(e.getMessage());
        }
    }

    @Override
    public void createBindings() {
        IntegerConverter intConverter = new IntegerConverter();
        DoubleConverter doubleConverter = new DoubleConverter("%f");
        LengthConverter lengthConverter = new LengthConverter();

        addWrappedBinding(feeder, "part", partCombo, "selectedItem");
        addWrappedBinding(feeder, "pin", pinField, "text", intConverter);
        addWrappedBinding(feeder, "pitchMm", pitchCombo, "selectedItem");
        addWrappedBinding(feeder, "feedPosition", feedPositionField, "text", intConverter);
        addWrappedBinding(feeder, "coverSlideSpeed", coverSlideSpeedField, "text", doubleConverter);

        MutableLocationProxy coverStartLocation = new MutableLocationProxy();
        bind(UpdateStrategy.READ_WRITE, feeder, "coverStartLocation",
                coverStartLocation, "location");
        addWrappedBinding(coverStartLocation, "lengthX", coverStartXField, "text",
                lengthConverter);
        addWrappedBinding(coverStartLocation, "lengthY", coverStartYField, "text",
                lengthConverter);
        addWrappedBinding(coverStartLocation, "lengthZ", coverStartZField, "text",
                lengthConverter);

        MutableLocationProxy firstPickLocation = new MutableLocationProxy();
        bind(UpdateStrategy.READ_WRITE, feeder, "firstPickLocation",
                firstPickLocation, "location");
        addWrappedBinding(firstPickLocation, "lengthX", firstPickXField, "text",
                lengthConverter);
        addWrappedBinding(firstPickLocation, "lengthY", firstPickYField, "text",
                lengthConverter);
        addWrappedBinding(firstPickLocation, "lengthZ", firstPickZField, "text",
                lengthConverter);

        ComponentDecorators.decorateWithAutoSelect(pinField);
        ComponentDecorators.decorateWithAutoSelect(feedPositionField);
        ComponentDecorators.decorateWithAutoSelect(coverSlideSpeedField);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(coverStartXField);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(coverStartYField);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(coverStartZField);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(firstPickXField);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(firstPickYField);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(firstPickZField);
    }

    /** Apply pending edits so machine moves always use what is on screen. */
    private void applyBeforeMove(ActionEvent e) {
        applyAction.actionPerformed(e);
        refreshReadouts();
    }

    private final Action resetFeedPositionAction = new AbstractAction("Reset to start") {
        @Override
        public void actionPerformed(ActionEvent e) {
            feeder.setFeedPosition(0);
            feedPositionField.setText("0");
            refreshReadouts();
        }
    };

    private final Action backOneAction = new AbstractAction("Back 1") {
        @Override
        public void actionPerformed(ActionEvent e) {
            feeder.setFeedPosition(feeder.getFeedPosition() - 1);
            feedPositionField.setText(Integer.toString(feeder.getFeedPosition()));
            refreshReadouts();
        }
    };

    /** The nozzle the manual steps operate with: the nozzle selected in the
     *  machine controls, falling back to the default head's default nozzle. */
    private Nozzle selectedNozzle() throws Exception {
        HeadMountable tool = MainFrame.get().getMachineControls().getSelectedTool();
        if (tool instanceof Nozzle) {
            return (Nozzle) tool;
        }
        return Configuration.get().getMachine().getDefaultHead().getDefaultNozzle();
    }

    private final Action step1Action = new AbstractAction("1. Nozzle above plunge (Safe Z)") {
        @Override
        public void actionPerformed(ActionEvent e) {
            applyBeforeMove(e);
            UiUtils.submitUiMachineTask(() -> {
                feeder.stepMoveAbovePlungePoint(selectedNozzle());
            });
        }
    };

    private final Action step2Action = new AbstractAction("2. Plunge to Cover Z") {
        @Override
        public void actionPerformed(ActionEvent e) {
            applyBeforeMove(e);
            UiUtils.submitUiMachineTask(() -> {
                feeder.stepPlungeToCoverZ(selectedNozzle());
            });
        }
    };

    private final Action step3Action = new AbstractAction("3. Slide open SLOWLY (−Y south)") {
        @Override
        public void actionPerformed(ActionEvent e) {
            applyBeforeMove(e);
            UiUtils.submitUiMachineTask(() -> {
                feeder.stepSlideCoverOpen(selectedNozzle());
            });
        }
    };

    private final Action step4Action = new AbstractAction("4. Retract Nozzle (Safe Z)") {
        @Override
        public void actionPerformed(ActionEvent e) {
            applyBeforeMove(e);
            UiUtils.submitUiMachineTask(() -> {
                feeder.stepRetractCoverNozzle(selectedNozzle());
            });
        }
    };

    private final Action fullCycleAction = new AbstractAction("Full Cover Slide Cycle") {
        @Override
        public void actionPerformed(ActionEvent e) {
            applyBeforeMove(e);
            UiUtils.submitUiMachineTask(() -> {
                feeder.slideCoverOpen(selectedNozzle(), feeder.getNextPickLocation());
            });
        }
    };

    private final Action moveCameraToOriginAction = new AbstractAction("Camera to Origin") {
        @Override
        public void actionPerformed(ActionEvent e) {
            applyBeforeMove(e);
            UiUtils.submitUiMachineTask(() -> {
                Camera camera = MainFrame.get().getMachineControls().getSelectedTool()
                        .getHead().getDefaultCamera();
                MovableUtils.moveToLocationAtSafeZ(camera,
                        feeder.getOriginLocation().derive(null, null, 0.0, null));
            });
        }
    };

    private final Action moveCameraToPickupAction = new AbstractAction("Camera to Next Pickup") {
        @Override
        public void actionPerformed(ActionEvent e) {
            applyBeforeMove(e);
            UiUtils.submitUiMachineTask(() -> {
                Camera camera = MainFrame.get().getMachineControls().getSelectedTool()
                        .getHead().getDefaultCamera();
                MovableUtils.moveToLocationAtSafeZ(camera,
                        feeder.getNextPickLocation().derive(null, null, 0.0, null));
            });
        }
    };

    private final Action nozzleAbovePickupAction =
            new AbstractAction("Nozzle above Next Pickup (Safe Z)") {
        @Override
        public void actionPerformed(ActionEvent e) {
            applyBeforeMove(e);
            UiUtils.submitUiMachineTask(() -> {
                HeadMountable tool = MainFrame.get().getMachineControls().getSelectedTool();
                Location next = feeder.getNextPickLocation();
                tool.moveToSafeZ();
                // NaN Z = keep the (safe) Z: hover above the part, no plunge.
                tool.moveTo(next.derive(null, null, Double.NaN, null));
            });
        }
    };
}
