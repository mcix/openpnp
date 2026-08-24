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

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

import javax.imageio.ImageIO;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;

import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.hwgc.wizards.HwgcDvrCameraConfigurationWizard;
import org.openpnp.machine.reference.camera.ReferenceCamera;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Camera;
import org.openpnp.spi.PropertySheetHolder;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;

/**
 * Camera driver for HWGC DVR capture card channels (hwsys.dll).
 * Captures 1920x1080 AHD frames from the Cyclone IV FPGA + NVP6114 PCI-E card.
 *
 * Frames are captured in memory via the SDK's RAW stream callback: a capture arms a
 * per-channel latch, the callback copies the next YUYV frame for that channel, and the
 * capture thread converts it to BGR with the SDK's ChangeYUVToRGB. The legacy
 * SaveCaptureImage BMP-file path is kept only as a fallback if no frame arrives.
 *
 * Typical channel mapping on SMT550:
 *   - Channels 0-3: Down-looking cameras (fiducial/mark detection)
 *   - Channel 4: Up-looking camera (component alignment)
 *   - Channels 5-7: Unused (no camera connected)
 */
public class HwgcDvrCamera extends ReferenceCamera {

    @Attribute(required = false)
    private int channel = 0;

    private static final int MAX_CHANNELS = 8;
    private static final int FRAME_WIDTH = 1920;
    private static final int FRAME_HEIGHT = 1080;
    private static final int FRAME_FPS = 25;
    private static final int FRAME_WAIT_MS = 400;

    // Shared singleton — one InitHwDSPs() call for all camera instances
    private static HwgcDvrSdk sharedSdk;
    private static int sharedChannelCount;
    private static int openCount;
    private static final Object LOCK = new Object();
    private static final int[] channelHandles = new int[MAX_CHANNELS];

    // Per-channel snapshot state, written by the DLL's streaming thread
    private static final Memory[] yuvBuffers = new Memory[MAX_CHANNELS];
    private static final int[] frameWidths = new int[MAX_CHANNELS];
    private static final int[] frameHeights = new int[MAX_CHANNELS];
    private static final AtomicReferenceArray<CountDownLatch> pendingCaptures =
            new AtomicReferenceArray<>(MAX_CHANNELS);

    // Reused YUV->BGR conversion buffer, guarded by CONVERT_LOCK
    private static Memory rgbBuffer;
    private static final Object CONVERT_LOCK = new Object();

    // The DLL keeps a raw pointer to the callback stub (there is no unregister export),
    // so the stub must never be garbage collected — hold a static strong reference.
    private static HwgcDvrSdk.RawStreamCallback rawCallback;

    private boolean opened;
    private File tempDir;

    public HwgcDvrCamera() {
    }

    @Override
    public synchronized BufferedImage internalCapture() {
        if (!ensureOpen()) {
            return null;
        }

        if (channel >= sharedChannelCount) {
            Logger.warn("HWGC DVR: channel {} not available (total: {})", channel, sharedChannelCount);
            return null;
        }

        BufferedImage img = captureViaCallback();
        if (img == null) {
            Logger.warn("HWGC DVR: ch{} callback capture timed out, trying BMP fallback", channel);
            img = captureViaBmp();
        }
        return img;
    }

    /**
     * Arm the channel's latch and wait for the streaming callback to deliver the next
     * frame, then convert it. Costs ~one frame period (40 ms at 25 fps) of latency and
     * a few ms of conversion — no disk I/O.
     */
    private BufferedImage captureViaCallback() {
        CountDownLatch latch = new CountDownLatch(1);
        pendingCaptures.set(channel, latch);
        try {
            if (!latch.await(FRAME_WAIT_MS, TimeUnit.MILLISECONDS)) {
                pendingCaptures.set(channel, null);
                return null;
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pendingCaptures.set(channel, null);
            return null;
        }

        int width = frameWidths[channel];
        int height = frameHeights[channel];
        if (width <= 0 || height <= 0) {
            return null;
        }
        int rgbSize = width * height * 3;
        synchronized (CONVERT_LOCK) {
            if (rgbBuffer == null || rgbBuffer.size() < rgbSize) {
                rgbBuffer = new Memory(rgbSize);
            }
            sharedSdk.ChangeYUVToRGB(yuvBuffers[channel], rgbBuffer, width, height);
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            byte[] data = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
            rgbBuffer.read(0, data, 0, rgbSize);
            Logger.trace("HWGC DVR: ch{} captured {}x{} via callback", channel, width, height);
            return img;
        }
    }

    /**
     * Runs on the DLL's streaming thread for every frame on every channel —
     * must stay cheap when no capture is pending.
     */
    private static void onRawFrame(int ch, Pointer dataBuf, int width, int height) {
        if (ch < 0 || ch >= MAX_CHANNELS || dataBuf == null) {
            return;
        }
        CountDownLatch latch = pendingCaptures.get(ch);
        if (latch == null) {
            return;
        }
        long size = (long) width * height * 2;
        Memory buf = yuvBuffers[ch];
        if (buf == null || buf.size() < size) {
            buf = new Memory(size);
            yuvBuffers[ch] = buf;
        }
        // native-to-native bulk copy, no Java heap involved
        ByteBuffer src = dataBuf.getByteBuffer(0, size);
        ByteBuffer dst = buf.getByteBuffer(0, size);
        dst.put(src);
        frameWidths[ch] = width;
        frameHeights[ch] = height;
        if (pendingCaptures.compareAndSet(ch, latch, null)) {
            latch.countDown();
        }
    }

    /**
     * Legacy capture path: the DLL writes a BMP to disk which is patched and decoded.
     * Slow (~40-80 ms plus disk I/O) — only used if the callback delivers no frame.
     */
    private BufferedImage captureViaBmp() {
        String path = new File(tempDir, "dvr_ch" + channel + ".bmp").getAbsolutePath();

        int ret;
        synchronized (LOCK) {
            ret = sharedSdk.SaveCaptureImage(channelHandles[channel], path);
        }

        if (ret != 0) {
            Logger.warn("HWGC DVR: SaveCaptureImage ch{} returned {}", channel, ret);
            return null;
        }

        File f = new File(path);
        if (!f.exists() || f.length() < 100) {
            Logger.warn("HWGC DVR: ch{} file missing or too small: exists={}, size={}",
                    channel, f.exists(), f.exists() ? f.length() : 0);
            return null;
        }

        try {
            // Fix BMP header — hwsys.dll writes incorrect file size fields
            try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
                long realSize = raf.length();
                byte[] sizeBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt((int) realSize).array();
                raf.seek(2);
                raf.write(sizeBytes);
                byte[] imgSize = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt((int) (realSize - 54)).array();
                raf.seek(34);
                raf.write(imgSize);
            }
            BufferedImage img = ImageIO.read(f);
            if (img == null) {
                Logger.warn("HWGC DVR: ch{} ImageIO.read returned null for {} ({} bytes)",
                        channel, path, f.length());
            } else {
                Logger.trace("HWGC DVR: ch{} captured {}x{} via BMP", channel, img.getWidth(), img.getHeight());
            }
            return img;
        }
        catch (Exception e) {
            Logger.warn("HWGC DVR: failed to read BMP for ch{}: {}", channel, e.getMessage());
            return null;
        }
    }

    @Override
    protected synchronized boolean ensureOpen() {
        if (opened) {
            return true;
        }
        synchronized (LOCK) {
            if (sharedSdk == null) {
                sharedSdk = HwgcDvrSdk.tryLoad();
                if (sharedSdk == null) {
                    Logger.error("HWGC DVR: hwsys.dll not found. "
                            + "Set -Djna.library.path to the DLL directory.");
                    return false;
                }

                // Init with retry — first attempt may fail if called too early.
                // OEM init order: SetMax_VideoSize -> InitHwDSPs -> VideoChannelOpen
                // per channel -> RegisterRAWDirectCallback.
                boolean initialized = false;
                for (int attempt = 0; attempt < 3; attempt++) {
                    try {
                        sharedSdk.SetMax_VideoSize(FRAME_WIDTH, FRAME_HEIGHT);
                        sharedSdk.InitHwDSPs();
                        sharedChannelCount = Math.min(sharedSdk.GetVideoTotalChannels(), MAX_CHANNELS);
                        Logger.info("HWGC DVR: initialized, {} channels available",
                                sharedChannelCount);

                        // Open ALL channels at once (DVR hardware requires batch init)
                        boolean anySuccess = false;
                        for (int i = 0; i < sharedChannelCount; i++) {
                            try {
                                channelHandles[i] = sharedSdk.VideoChannelOpen(
                                        i, FRAME_WIDTH, FRAME_HEIGHT, FRAME_FPS);
                                anySuccess = true;
                            }
                            catch (Error e) {
                                Logger.trace("HWGC DVR: channel {} failed on attempt {}: {}",
                                        i, attempt + 1, e.getMessage());
                            }
                        }
                        if (anySuccess) {
                            if (rawCallback == null) {
                                rawCallback = new HwgcDvrSdk.RawStreamCallback() {
                                    @Override
                                    public void invoke(int ch, Pointer dataBuf, int frameType,
                                            int width, int height, Pointer context) {
                                        onRawFrame(ch, dataBuf, width, height);
                                    }
                                };
                            }
                            sharedSdk.RegisterRAWDirectCallback(rawCallback, Pointer.NULL);
                            initialized = true;
                            break;
                        }
                    }
                    catch (Error e) {
                        Logger.warn("HWGC DVR: init attempt {} failed: {}",
                                attempt + 1, e.getMessage());
                    }
                    // Wait before retry
                    try {
                        Thread.sleep(1000);
                    }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        sharedSdk = null;
                        return false;
                    }
                    // Re-init on next attempt
                    try {
                        sharedSdk.DeInitHwDSPs();
                    }
                    catch (Error e) {
                        // ignore
                    }
                }
                if (!initialized) {
                    Logger.error("HWGC DVR: failed to initialize after 3 attempts");
                    sharedSdk = null;
                    return false;
                }
            }
            openCount++;
        }

        tempDir = new File(System.getProperty("java.io.tmpdir"), "hwgc_dvr_cap");
        tempDir.mkdirs();
        opened = true;
        Logger.info("HWGC DVR camera opened: channel {}", channel);
        // Let the parent class start the capture thread
        return super.ensureOpen();
    }

    @Override
    public synchronized void close() throws java.io.IOException {
        if (!opened) {
            return;
        }
        opened = false;
        synchronized (LOCK) {
            openCount--;
            if (openCount <= 0 && sharedSdk != null) {
                for (int i = 0; i < sharedChannelCount; i++) {
                    try {
                        sharedSdk.StopVideoCapture(channelHandles[i]);
                    }
                    catch (Error e) {
                        // ignore
                    }
                }
                try {
                    sharedSdk.DeInitHwDSPs();
                }
                catch (Error e) {
                    // ignore
                }
                sharedSdk = null;
                openCount = 0;
                Logger.info("HWGC DVR: shutdown");
            }
        }
        super.close();
    }

    // ── Configuration ──

    public int getChannel() {
        return channel;
    }

    public void setChannel(int channel) {
        this.channel = channel;
    }

    /**
     * Tears down and re-initializes every {@link HwgcDvrCamera} attached to
     * the current machine. Use when the DVR feed freezes — closes all DVR
     * cameras (the last one triggers a full SDK teardown via
     * {@link #close()}), then reopens each so the next capture re-runs
     * {@link #ensureOpen()} and re-inits the hwsys.dll SDK end-to-end.
     */
    public static void reopenAll() throws Exception {
        List<HwgcDvrCamera> cams = new ArrayList<>();
        for (Camera c : Configuration.get().getMachine().getAllCameras()) {
            if (c instanceof HwgcDvrCamera) {
                cams.add((HwgcDvrCamera) c);
            }
        }
        Logger.info("HWGC DVR: reopening {} camera(s)", cams.size());
        for (HwgcDvrCamera c : cams) {
            try {
                c.close();
            }
            catch (Exception e) {
                Logger.warn("HWGC DVR: close failed for {}: {}", c.getName(), e.getMessage());
            }
        }
        for (HwgcDvrCamera c : cams) {
            try {
                c.open();
            }
            catch (Exception e) {
                Logger.warn("HWGC DVR: open failed for {}: {}", c.getName(), e.getMessage());
            }
        }
    }

    @Override
    public Wizard getConfigurationWizard() {
        return new HwgcDvrCameraConfigurationWizard(this);
    }

    @Override
    public String getPropertySheetHolderTitle() {
        return getClass().getSimpleName() + " " + getName();
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        return null;
    }
}
