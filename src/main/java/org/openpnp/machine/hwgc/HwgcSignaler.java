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

package org.openpnp.machine.hwgc;

import java.awt.AWTEvent;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.hwgc.wizards.HwgcSignalerConfigurationWizard;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Driver;
import org.openpnp.spi.base.AbstractJobProcessor;
import org.openpnp.spi.base.AbstractSignaler;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;

/**
 * Drives the HWGC stack light and buzzer from the job processor state:
 * <ul>
 *   <li>RUNNING  — green on, yellow and red off.</li>
 *   <li>ERROR    — red on (e.g. failed pick/align); turned off again as soon as the
 *                  user moves the mouse in the OpenPnP window.</li>
 *   <li>FINISHED — buzzer beeps 3 times, green stays on, yellow flashes until the
 *                  user moves the mouse.</li>
 *   <li>STOPPED  — all stack lights off.</li>
 * </ul>
 * Lamp numbering on the wire (ALARM_LAMP 0x43): 0=green, 1=yellow, 2=red.
 */
public class HwgcSignaler extends AbstractSignaler {

    public static final int LAMP_GREEN = 0;
    public static final int LAMP_YELLOW = 1;
    public static final int LAMP_RED = 2;

    @Attribute(required = false)
    protected int beepCount = 3;

    @Attribute(required = false)
    protected int beepOnMs = 200;

    @Attribute(required = false)
    protected int beepOffMs = 200;

    @Attribute(required = false)
    protected int flashIntervalMs = 500;

    /**
     * Grace period after an alert is raised during which mouse movement is ignored,
     * so an already-moving mouse doesn't instantly clear the alert.
     */
    @Attribute(required = false)
    protected int mouseArmDelayMs = 1500;

    /** Serializes all lamp/buzzer serial traffic off the job processor thread. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hwgc-signaler");
        t.setDaemon(true);
        return t;
    });

    private volatile AbstractJobProcessor.State lastState;
    private volatile boolean flashYellow;
    private volatile Thread flashThread;

    // Mouse-clears-alert handling
    private volatile boolean mouseArmed;
    private volatile long armedAtMs;
    private AWTEventListener mouseListener;

    @Override
    public void signalJobProcessorState(AbstractJobProcessor.State state) {
        // RUNNING is fired on every job step — only react to actual transitions.
        if (state == lastState) {
            return;
        }
        lastState = state;
        executor.submit(() -> handleState(state));
    }

    /**
     * Force a state signal for testing (used by the configuration wizard buttons).
     * Bypasses the transition dedup so the same test can be run repeatedly.
     */
    public void test(AbstractJobProcessor.State state) {
        lastState = state;
        executor.submit(() -> handleState(state));
    }

    private void handleState(AbstractJobProcessor.State state) {
        try {
            switch (state) {
                case RUNNING:
                    disarmMouseClear();
                    stopFlash();
                    lamp(LAMP_RED, false);
                    lamp(LAMP_YELLOW, false);
                    lamp(LAMP_GREEN, true);
                    break;
                case ERROR:
                    lamp(LAMP_RED, true);
                    armMouseClear();
                    break;
                case FINISHED:
                    lamp(LAMP_RED, false);
                    lamp(LAMP_GREEN, true);
                    beep(beepCount);
                    startFlash();
                    armMouseClear();
                    break;
                case STOPPED:
                    disarmMouseClear();
                    stopFlash();
                    lamp(LAMP_RED, false);
                    lamp(LAMP_YELLOW, false);
                    lamp(LAMP_GREEN, false);
                    break;
            }
        }
        catch (Exception e) {
            Logger.warn("HWGC signaler: failed to signal state {}: {}", state, e.getMessage());
        }
    }

    // ── Mouse movement clears the current alert ──

    private synchronized void armMouseClear() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        armedAtMs = System.currentTimeMillis();
        mouseArmed = true;
        if (mouseListener == null) {
            mouseListener = (AWTEvent event) -> {
                if (mouseArmed
                        && System.currentTimeMillis() - armedAtMs > mouseArmDelayMs) {
                    mouseArmed = false;
                    executor.submit(this::clearAlert);
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(mouseListener,
                    AWTEvent.MOUSE_MOTION_EVENT_MASK);
        }
    }

    private void disarmMouseClear() {
        mouseArmed = false;
    }

    /** User moved the mouse: red off, stop yellow flashing. Green is left as-is. */
    private void clearAlert() {
        try {
            stopFlash();
            lamp(LAMP_RED, false);
            lamp(LAMP_YELLOW, false);
        }
        catch (Exception e) {
            Logger.warn("HWGC signaler: failed to clear alert: {}", e.getMessage());
        }
    }

    // ── Yellow flash ──

    private void startFlash() {
        stopFlash();
        flashYellow = true;
        Thread t = new Thread(() -> {
            boolean on = true;
            while (flashYellow) {
                try {
                    lamp(LAMP_YELLOW, on);
                    on = !on;
                    Thread.sleep(flashIntervalMs);
                }
                catch (InterruptedException e) {
                    break;
                }
                catch (Exception e) {
                    Logger.warn("HWGC signaler: flash error: {}", e.getMessage());
                    break;
                }
            }
        }, "hwgc-stacklight-flash");
        t.setDaemon(true);
        flashThread = t;
        t.start();
    }

    private void stopFlash() {
        flashYellow = false;
        Thread t = flashThread;
        flashThread = null;
        if (t != null) {
            t.interrupt();
            // Wait for the flasher to die so a queued "on" can't land after our "off"
            try {
                t.join(1000);
            }
            catch (InterruptedException ignored) {
            }
        }
    }

    // ── Hardware access ──

    private HwgcDriver getDriver() {
        for (Driver d : Configuration.get().getMachine().getDrivers()) {
            if (d instanceof HwgcDriver) {
                return (HwgcDriver) d;
            }
        }
        return null;
    }

    private void lamp(int lampNo, boolean on) throws Exception {
        HwgcDriver driver = getDriver();
        if (driver == null || !driver.isConnected()) {
            return;
        }
        driver.sendAlarmLamp(lampNo, on);
    }

    private void beep(int times) throws Exception {
        HwgcDriver driver = getDriver();
        if (driver == null || !driver.isConnected()) {
            return;
        }
        for (int i = 0; i < times; i++) {
            driver.sendBuzzer(true);
            Thread.sleep(beepOnMs);
            driver.sendBuzzer(false);
            Thread.sleep(beepOffMs);
        }
    }

    @Override
    public Wizard getConfigurationWizard() {
        return new HwgcSignalerConfigurationWizard(this);
    }

    public int getBeepCount() { return beepCount; }
    public void setBeepCount(int v) { this.beepCount = v; }

    public int getBeepOnMs() { return beepOnMs; }
    public void setBeepOnMs(int v) { this.beepOnMs = v; }

    public int getBeepOffMs() { return beepOffMs; }
    public void setBeepOffMs(int v) { this.beepOffMs = v; }

    public int getFlashIntervalMs() { return flashIntervalMs; }
    public void setFlashIntervalMs(int v) { this.flashIntervalMs = v; }

    public int getMouseArmDelayMs() { return mouseArmDelayMs; }
    public void setMouseArmDelayMs(int v) { this.mouseArmDelayMs = v; }
}
