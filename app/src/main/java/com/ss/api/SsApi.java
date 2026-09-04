package com.ss.api;


//import android.annotation.SuppressLint;
//import android.app.AlarmManager;
//import android.app.PendingIntent;
//import android.content.Context;
//import android.content.Intent;
//import android.os.Build;
//import android.util.DisplayMetrics;
//import android.util.Log;
//import android.view.WindowManager;

//import com.ss.api.serialport.SerialPort;
import com.ss.api.shell.Command;
import com.ss.api.shell.Shell;

public class SsApi {
	public static final String VERSION = "20220628";
	private static com.ss.api.SsApi mInstance;

	public SsApi() {
	}

	public static com.ss.api.SsApi getInstance() {
		if (mInstance == null) {
			mInstance = new com.ss.api.SsApi();
		}

		return mInstance;
	}

	public Command execCmd(String cmd) {
		Shell mShell = new Shell();
		Command command = mShell.execute(cmd);
		mShell.close();
		return mShell.execute(command);
	}

	public Command execSuCmd(String cmd) {
		Shell mShell = new Shell();
		mShell.getRoot();
		if (mShell.isRootShell()) {
			Command command = mShell.execute(cmd);
			mShell.close();
			return command;
		} else {
			mShell.close();
			return new Command();
		}
	}

	public int gpioParse(String gpioStr) {
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

	public boolean gpioCtrl(int gpio, String direction, int value) {
		String exportPath = "";
		String directionPath = "";
		if (!this.isGpioOccupied(gpio)) {
			exportPath = "echo " + gpio + " > /sys/class/gpio/export";
			directionPath = "echo " + direction + " >  /sys/class/gpio/gpio" + gpio + "/direction";
		}

		String valuePath = "echo " + value + " > /sys/class/gpio/gpio" + gpio + "/value";
		Command cmd = this.execSuCmd(exportPath + "\n" + directionPath + "\n" + valuePath + "\n");
		return cmd.exitStatus == 0;
	}

	public boolean gpioRemove(int gpio) {
		String unexportPath = "echo " + gpio + " > /sys/class/gpio/unexport";
		Command cmd = this.execSuCmd(unexportPath + "\n");
		return cmd.exitStatus == 0;
	}

	public int gpioRead(int gpio) {
		String valuePath = "cat  /sys/class/gpio/gpio" + gpio + "/value";
		Command cmd = this.execSuCmd(valuePath + "\n");
		return cmd.exitStatus == 0 && cmd.output != null && cmd.output.length > 0 ? Integer.parseInt(cmd.output[0]) : -1;
	}

	public boolean isGpioOccupied(int gpio) {
		Command cmd = this.execSuCmd("cat /sys/kernel/debug/gpio | grep gpio-" + gpio);
		return cmd.exitStatus == 0 && cmd.output.length > 0;
	}

}