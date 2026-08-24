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

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import org.pmw.tinylog.Logger;

/**
 * JNA interface for hwsys.dll — the HWGC DVR capture card SDK.
 * Controls the Altera Cyclone IV FPGA + Nextchip NVP6114 PCI-E capture card
 * that interfaces with up to 8 AHD analog cameras (1920x1080).
 *
 * Signatures recovered from the OEM software (QIGN_COMMON.exe / DvrshMethod.cs,
 * decompiled with ILSpy). All functions are cdecl. Notes:
 * <ul>
 *   <li>{@link #VideoChannelOpen} returns a channel <em>handle</em>, which is what
 *       {@link #SaveCaptureImage} and {@link #StopVideoCapture} expect.</li>
 *   <li>{@code StartVideoPreview(hChannel, HWND, RECT*)} only renders video into a
 *       window and is not needed for capture; it is deliberately not bound here.</li>
 *   <li>{@link #RegisterRAWDirectCallback} delivers every frame of every open channel
 *       in memory as YUV 4:2:2 (YUYV), width*height*2 bytes — the preferred capture
 *       path (no disk I/O, unlike SaveCaptureImage).</li>
 * </ul>
 *
 * Method names match the DLL exports exactly (PascalCase).
 * Checkstyle MethodName rule is suppressed for this file via pom.xml.
 */
public interface HwgcDvrSdk extends Library {

    /**
     * Per-frame callback: {@code buf} holds a YUYV frame of {@code width*height*2} bytes.
     * Invoked on the DLL's streaming thread for every frame on every open channel,
     * so implementations must stay cheap when no capture is pending.
     */
    interface RawStreamCallback extends Callback {
        void invoke(int ch, Pointer dataBuf, int frameType, int width, int height, Pointer context);
    }

    int InitHwDSPs();
    int DeInitHwDSPs();
    int GetVideoTotalChannels();
    int VideoChannelOpen(int nChannel, int w, int h, int fps);
    int StopVideoCapture(int hChannel);
    int SaveCaptureImage(int hChannel, String filename);
    int RegisterRAWDirectCallback(RawStreamCallback cb, Pointer context);
    void ChangeYUVToRGB(Pointer yuvData, Pointer rgbData, int width, int height);
    void ChangeYUVToGrayRGB(Pointer yuvData, Pointer grayData, int width, int height);
    void SetMax_VideoSize(int width, int height);

    /**
     * Load the SDK. Returns null if the DLL is not available.
     */
    static HwgcDvrSdk tryLoad() {
        try {
            return Native.load("hwsys", HwgcDvrSdk.class);
        }
        catch (UnsatisfiedLinkError e) {
            Logger.warn("HWGC DVR: hwsys.dll not found: {}", e.getMessage());
            return null;
        }
    }
}
