/*
 * Configuration wizard for {@link DeltaProtoStripFeeder}.
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

import org.openpnp.gui.MainFrame;
import org.openpnp.gui.components.ComponentDecorators;
import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.gui.support.IntegerConverter;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.util.MovableUtils;
import org.openpnp.util.UiUtils;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;

@SuppressWarnings("serial")
public class DeltaProtoStripFeederConfigurationWizard extends AbstractConfigurationWizard {
    private final DeltaProtoStripFeeder feeder;

    private JTextField pinField;
    private JComboBox<Double> pitchCombo;
    private JTextField feedPositionField;
    private final JLabel pinLocationLabel = new JLabel(" ");
    private final JLabel nextPickupLabel = new JLabel(" ");

    public DeltaProtoStripFeederConfigurationWizard(DeltaProtoStripFeeder feeder) {
        this.feeder = feeder;
        contentPanel.add(buildStripPanel());
        refreshReadouts();
    }

    private JPanel buildStripPanel() {
        JPanel panel = new JPanel();
        panel.setBorder(new TitledBorder(null,
                "Plankje Strip", TitledBorder.LEADING, TitledBorder.TOP,
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
                        FormSpecs.RELATED_GAP_COLSPEC, },
                new RowSpec[] {
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, }));

        panel.add(new JLabel("Pin (1-40)"), "2, 2, right, default");
        pinField = new JTextField(6);
        panel.add(pinField, "4, 2, fill, default");

        panel.add(new JLabel("Pitch (mm)"), "6, 2, right, default");
        pitchCombo = new JComboBox<>(new Double[] {4.0, 2.0});
        panel.add(pitchCombo, "8, 2, fill, default");

        panel.add(new JLabel("Feed position (0 = first)"), "2, 4, right, default");
        feedPositionField = new JTextField(6);
        panel.add(feedPositionField, "4, 4, fill, default");
        JButton resetBtn = new JButton(resetFeedPositionAction);
        panel.add(resetBtn, "6, 4");
        JButton backOneBtn = new JButton(backOneAction);
        panel.add(backOneBtn, "8, 4");

        panel.add(new JLabel("Pin location:"), "2, 6, right, default");
        panel.add(pinLocationLabel, "4, 6, 5, 1");

        panel.add(new JLabel("Next pickup:"), "2, 8, right, default");
        panel.add(nextPickupLabel, "4, 8, 5, 1");

        JButton camPinBtn = new JButton(moveCameraToPinAction);
        panel.add(camPinBtn, "2, 10");
        JButton camPickupBtn = new JButton(moveCameraToPickupAction);
        panel.add(camPickupBtn, "4, 10, 3, 1");

        return panel;
    }

    private void refreshReadouts() {
        try {
            Location pin = feeder.getPinLocation();
            pinLocationLabel.setText(pin == null ? "pin outside 1..40"
                    : String.format(Locale.US, "X %.3f  Y %.3f  Z %.3f",
                            pin.getX(), pin.getY(), pin.getZ()));
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

        addWrappedBinding(feeder, "pin", pinField, "text", intConverter);
        addWrappedBinding(feeder, "pitchMm", pitchCombo, "selectedItem");
        addWrappedBinding(feeder, "feedPosition", feedPositionField, "text", intConverter);

        ComponentDecorators.decorateWithAutoSelect(pinField);
        ComponentDecorators.decorateWithAutoSelect(feedPositionField);
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

    private final Action moveCameraToPinAction = new AbstractAction("Camera to Pin") {
        @Override
        public void actionPerformed(ActionEvent e) {
            applyAction.actionPerformed(e);
            refreshReadouts();
            UiUtils.submitUiMachineTask(() -> {
                Location pin = feeder.getPinLocation();
                if (pin == null) {
                    throw new Exception("Pin outside plankje range 1..40");
                }
                Camera camera = MainFrame.get().getMachineControls().getSelectedTool()
                        .getHead().getDefaultCamera();
                MovableUtils.moveToLocationAtSafeZ(camera, pin.derive(null, null, 0.0, null));
            });
        }
    };

    private final Action moveCameraToPickupAction = new AbstractAction("Camera to Next Pickup") {
        @Override
        public void actionPerformed(ActionEvent e) {
            applyAction.actionPerformed(e);
            refreshReadouts();
            UiUtils.submitUiMachineTask(() -> {
                Location next = feeder.getNextPickLocation();
                Camera camera = MainFrame.get().getMachineControls().getSelectedTool()
                        .getHead().getDefaultCamera();
                MovableUtils.moveToLocationAtSafeZ(camera, next.derive(null, null, 0.0, null));
            });
        }
    };
}
