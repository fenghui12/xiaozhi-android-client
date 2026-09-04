package com.ss.api;

import com.ss.api.serialport.SerialPort;
import com.ss.api.shell.Command;
import com.ss.api.utils.StringUtils;
import com.ss.api.serialport.SerialPort.Callback;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;

public class HardwareCtrl {
    private static final String PATH_MODE_SWITCH = "/sys/devices/platform/relay_ctrl/gpio_relay";
    private static final String PATH_TP = "/dev/tp_ctrl";
    private static final String PATH_BRIGHTNESS = "/sys/class/backlight/backlight/brightness";
    private static final String PATH_GPIO_EXPORT = "/sys/class/gpio/export";

    private static volatile boolean newHWVersion_2_1 = false;
    private static volatile boolean newHWVersion_2_3 = false;

    public static Command execSuCmd(String cmd) {
        return SsApi.getInstance().execSuCmd(cmd);
    }

    public static int gpioParse(String gpioStr) {
        if (gpioStr != null && gpioStr.length() == 8) {
            gpioStr = gpioStr.toUpperCase();
            if (gpioStr.charAt(4) >= '0' && gpioStr.charAt(4) <= '8') {
                if (gpioStr.charAt(6) >= 'A' && gpioStr.charAt(6) <= 'D') {
                    return gpioStr.charAt(7) >= '0' && gpioStr.charAt(7) <= '7' ? (gpioStr.charAt(4) - 48) * 32 + (gpioStr.charAt(6) - 65) * 8 + (gpioStr.charAt(7) - 48) : -1;
                } else {
                    return -1;
                }
            } else {
                return -1;
            }
        } else {
            System.out.println("input gpio error!");
            return -1;
        }
    }

    public static void exportGpio(int gpio) throws IOException {
        File file = new File("/sys/class/gpio/gpio" + gpio + "");
        if (!file.exists()) {
            writeToDevice(new File(PATH_GPIO_EXPORT), gpio + "");
        }
    }

    public static void ctrlGpio(int gpio, String direction, int value) {
        String valuepath = "/sys/class/gpio/gpio" + gpio + "/value";
        String directionPath = "/sys/class/gpio/gpio" + gpio + "/direction";
//        writeToDevice(new File(PATH_GPIO_EXPORT), gpio + "");
        writeToDevice(new File(directionPath), direction);
        writeToDevice(new File(valuepath), value + "");
    }

    private static Class<?> systemProp = null;
    private static Method setValue = null;

    static {
        try {
            systemProp = Class.forName("android.os.SystemProperties");
            setValue = systemProp.getDeclaredMethod("get", String.class, String.class);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static String HardPropVersion = "";

    public static String readFromDevice(File device) {
        String result = "";
        if (device.exists()) {
            if ((!device.canRead() || !device.canWrite())) {
               excuseCmd("su root 'chmod 666 " + device.getAbsolutePath() + "'");
            }

            try {
                result = (new BufferedReader(new FileReader(device))).readLine();
            } catch (IOException var3) {
                var3.printStackTrace();
            }
        }

        return result;
    }

    private static boolean shellCommand(String command) {
        Command cmd = SsApi.getInstance().execSuCmd(command);
        return cmd.isSuccessful();
    }

    public static void writeToDevice(File device, String value) {
        if (device.exists()) {
//            if ((!device.canRead() || !device.canWrite())) {
//                excuseCmd("su root 'chmod 666 " + device.getAbsolutePath() + "'");
//            }
            excuseCmd("su root chmod 666 " + device.getAbsolutePath());
            FileOutputStream outputStream = null;

            try {
                outputStream = new FileOutputStream(device);
                outputStream.write(value.getBytes());
                outputStream.flush();
            } catch (FileNotFoundException var14) {
                var14.printStackTrace();
            } catch (IOException var15) {
                var15.printStackTrace();
            } finally {
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException var13) {
                        var13.printStackTrace();
                    }
                }

            }
        }

    }

    public static void setBrightness(int value) {
        if (value <= 255 && value >= 0) {
            writeToDevice(new File(PATH_BRIGHTNESS), value + "");
        } else {
            new IOException();
        }
    }

    public static void excuseCmd(String command) {
        Process process = null;
        DataOutputStream os = null;

        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            process.waitFor();
        } catch (Exception var12) {
            var12.printStackTrace();
        } finally {
            try {
                if (os != null) {
                    os.close();
                }

                if (process != null) {
                    process.destroy();
                }
            } catch (Exception var11) {
                var11.printStackTrace();
            }

        }

    }

    @Deprecated
    public static SerialPort openRs485Signal(File device, int baudrate, Callback callback) {
        SerialPort mSerialPort = null;
        if (device.exists()) {
//            if ((!device.canRead() || !device.canWrite()) ) {
//                excuseCmd("su root 'chmod 666 " + device.getAbsolutePath() + "'");
//            }
            excuseCmd("su root chmod 666 " + device.getAbsolutePath());
            try {
                mSerialPort = new SerialPort(device, baudrate, 0);
                mSerialPort.setCallback(callback);
            } catch (SecurityException var5) {
                var5.printStackTrace();
            } catch (IOException var6) {
                var6.printStackTrace();
            }
        }

        return mSerialPort;
    }

    @Deprecated
    public static SerialPort openRs485Signal(int baudrate, Callback callback) {
        SerialPort mSerialPort = null;
        File device = new File("/dev/ttyS4");
        if (device.exists()) {
//            if ((!device.canRead() || !device.canWrite()) ) {
//                excuseCmd("su root 'chmod 666 " + device.getAbsolutePath() + "'");
//            }
            excuseCmd("su root chmod 666 " + device.getAbsolutePath());
            try {
                mSerialPort = new SerialPort(device, baudrate, 0);
                mSerialPort.setCallback(callback);
            } catch (SecurityException var5) {
                var5.printStackTrace();
            } catch (IOException var6) {
                var6.printStackTrace();
            }
        }

        return mSerialPort;
    }

    @Deprecated
    public static void sendRs485Msg(SerialPort mSerialPort, String msg) {
        if (mSerialPort != null) {
            mSerialPort.sendMsg(msg);
        } else {
            new IOException();
        }

    }

    @Deprecated
    public static void sendRs485HexMsg(SerialPort mSerialPort, String msg) {
        if (mSerialPort != null) {
            mSerialPort.sendHexMsg(StringUtils.hexString2Bytes(msg));
        } else {
            new IOException();
        }

    }

    @Deprecated
    public static void closeRs485Signal(SerialPort mSerialPort) {
        if (mSerialPort != null) {
            try {
                mSerialPort.closeSerialPort();
            } catch (SecurityException var2) {
                var2.printStackTrace();
            } catch (Exception var3) {
                var3.printStackTrace();
            }
        }

    }

    public static SerialPort openSerialPortSignal(File device, int baudrate, Callback callback) {
        SerialPort mSerialPort = null;
        if (device.exists()) {
//            if ((!device.canRead() || !device.canWrite()) ) {
//                excuseCmd("su root 'chmod 666 " + device.getAbsolutePath() + "'");
//            }
            excuseCmd("su root chmod 666 " + device.getAbsolutePath());
            try {
                mSerialPort = new SerialPort(device, baudrate, 0);
                mSerialPort.setCallback(callback);
            } catch (SecurityException var5) {
                var5.printStackTrace();
            } catch (IOException var6) {
                var6.printStackTrace();
            }
        }

        return mSerialPort;
    }

    public static void sendSerialPortMsg(SerialPort mSerialPort, String msg) {
        if (mSerialPort != null) {
            mSerialPort.sendMsg(msg);
        } else {
            new IOException();
        }

    }

    public static void sendSerialPortHexMsg(SerialPort mSerialPort, String msg) {
        if (mSerialPort != null) {
            mSerialPort.sendHexMsg(StringUtils.hexString2Bytes(msg));
        } else {
            new IOException();
        }
    }

    public static void closeSerialPortSignal(SerialPort mSerialPort) {
        if (mSerialPort != null) {
            try {
                mSerialPort.closeSerialPort();
            } catch (SecurityException var2) {
                var2.printStackTrace();
            } catch (Exception var3) {
                var3.printStackTrace();
            }
        }
    }
}
