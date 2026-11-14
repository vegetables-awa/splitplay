package cn.splitplay.rawin;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.util.Arrays;
import java.util.List;

/**
 * The three Raw Input entry points that jna-platform does not declare.
 *
 * <p>Loaded with plain options (no A/W name mapper) so the exported names are used verbatim.
 * On x64 there is only one calling convention, so extending {@link StdCallLibrary} is harmless
 * and keeps the declaration correct on x86 too.</p>
 */
public interface RawInputApi extends StdCallLibrary {

    RawInputApi INSTANCE = Native.load("user32", RawInputApi.class);

    // RIDEV_* flags (winuser.h)
    int RIDEV_REMOVE = 0x00000001;
    int RIDEV_EXCLUDE = 0x00000010;
    int RIDEV_PAGEONLY = 0x00000020;
    int RIDEV_NOLEGACY = 0x00000030;
    int RIDEV_INPUTSINK = 0x00000100;
    int RIDEV_CAPTUREMOUSE = 0x00000200;
    int RIDEV_NOHOTKEYS = 0x00000200;
    int RIDEV_APPKEYS = 0x00000400;
    int RIDEV_EXINPUTSINK = 0x00001000;
    int RIDEV_DEVNOTIFY = 0x00002000;

    // RID_DEVICE_INFO / RIDI_* commands
    int RIDI_PREPARSEDDATA = 0x20000005;
    int RIDI_DEVICENAME = 0x20000007;
    int RIDI_DEVICEINFO = 0x2000000b;
    int RID_INPUT = 0x10000003;
    int RID_HEADER = 0x10000005;

    int RIM_TYPEMOUSE = 0;
    int RIM_TYPEKEYBOARD = 1;
    int RIM_TYPEHID = 2;

    // HID usage page / usages (for RegisterRawInputDevices)
    int HID_USAGE_PAGE_GENERIC = 0x01;
    int HID_USAGE_GENERIC_MOUSE = 0x02;
    int HID_USAGE_GENERIC_KEYBOARD = 0x06;

    /**
     * Registers the raw input devices. {@code dwFlags} with {@code RIDEV_INPUTSINK} requires
     * {@code hwndTarget} to be a valid window; events then arrive even without keyboard focus.
     */
    boolean RegisterRawInputDevices(RawInputDeviceRegistration[] devices, int numDevices, int size);

    /**
     * @param pData        target buffer, or null to query the required size
     * @param pcbSize      in: buffer size; out: bytes written / required
     * @param sizeHeader   sizeof(RAWINPUTHEADER)
     * @return bytes written, or (UINT)-1 on failure
     */
    int GetRawInputData(Pointer hRawInput, int command, Pointer pData, IntByReference pcbSize, int sizeHeader);

    /**
     * @param hDevice device handle from {@code GetRawInputDeviceList} or a WM_INPUT header
     * @param pData   target buffer, or null to query the required size (in WCHARs for RIDI_DEVICENAME)
     */
    int GetRawInputDeviceInfoW(Pointer hDevice, int command, Pointer pData, IntByReference pcbSize);

    /** RAWINPUTDEVICE. Packed as in winuser.h: size 16 on x64. */
    class RawInputDeviceRegistration extends Structure {
        public short usUsagePage;
        public short usUsage;
        public int dwFlags;
        public WinDef.HWND hwndTarget;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("usUsagePage", "usUsage", "dwFlags", "hwndTarget");
        }
    }
}
