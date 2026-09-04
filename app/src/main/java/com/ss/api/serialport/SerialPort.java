package com.ss.api.serialport;

import android.util.Log;

import com.ss.api.shell.Command;
import com.ss.api.shell.Shell;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class SerialPort {
    private static final String TAG = "SerialPort";
    private FileDescriptor mFd;
    private FileInputStream mFileInputStream;
    private FileOutputStream mFileOutputStream;
    private SerialPort.ReadThread mReadThread;
    private SerialPort.Callback mCallback;
    private boolean readInterrupted = true;

    public void setCallback(SerialPort.Callback callback) {
        this.mCallback = callback;
    }

    public SerialPort(File device, int baudrate, int flags) throws SecurityException, IOException {
        if (!device.canRead() || !device.canWrite()) {
            Shell mShell = new Shell();
            mShell.getRoot();
            if (!mShell.isRootShell()) {
                throw new IOException();
            }

            Command command = mShell.execute("chmod 666 " + device.getAbsolutePath());
            if (command.exitStatus != 0) {
                throw new IOException();
            }
        }

        int dataBits = 8;
        int parity = 0;
        int stopBits = 1;

        this.mFd = open(device.getAbsolutePath(), baudrate, dataBits, parity, stopBits, flags);
        if (this.mFd == null) {
            Log.e("SerialPort", "native open returns null");
            throw new IOException();
        } else {
            readInterrupted = true;
            this.mFileInputStream = new FileInputStream(this.mFd);
            this.mFileOutputStream = new FileOutputStream(this.mFd);
            this.mReadThread = new SerialPort.ReadThread();
            this.mReadThread.start();
        }
    }

    public void sendMsg(String cmd) {
        char[] text = new char[cmd.length()];

        for(int i = 0; i < cmd.length(); ++i) {
            text[i] = cmd.charAt(i);
        }

        try {
            this.mFileOutputStream.write((new String(text)).getBytes());
        } catch (IOException var4) {
            var4.printStackTrace();
        }

    }

    public void sendHexMsg(byte[] cmd) {
        try {
            this.mFileOutputStream.write(cmd);
        } catch (IOException var3) {
            var3.printStackTrace();
        }

    }

    public InputStream getInputStream() {
        return this.mFileInputStream;
    }

    public OutputStream getOutputStream() {
        return this.mFileOutputStream;
    }

    public void closeSerialPort() {
        readInterrupted = false;
        this.close();
    }

    private static native FileDescriptor open(String var0, int var1, int var2, int var3, int var4, int var5);

    private native void close();

    static {
        System.loadLibrary("ss_serialport");
    }

    private class ReadThread extends Thread {
        private ReadThread() {
        }

        public void run() {
            super.run();
            StringBuffer sb = new StringBuffer();
            while(true) {
                try {
                    if (SerialPort.this.mCallback != null) {

                        byte[] buffer = new byte[64];
                        if (SerialPort.this.mFileInputStream == null) {
                            return;
                        }

                        int size = SerialPort.this.mFileInputStream.read(buffer);
                        if (size > 0) {
                            SerialPort.this.mCallback.onDataReceived(buffer, size);
                        }

                    }

                } catch (Exception var3) {
                    return;
                }
            }

        }
    }

    public interface Callback {
        void onDataReceived(byte[] var1, int var2);
    }
}
