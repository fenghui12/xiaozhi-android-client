package com.ss.api.shell;

public class Command {
    public static final Command REBOOT = new Command("reboot");
    public static final Command REBOOT_SOFT = new Command("pkill zygote");
    public static final Command REBOOT_RECOVERY = new Command("reboot recovery");
    public static final Command POWEROFF = new Command("reboot -p");
    public String input;
    public String[] output;
    public int exitStatus;

    public Command(String input) {
        if (input == null) {
            throw new NullPointerException("Cannot use a null input for the command.");
        } else {
            this.input = input;
        }
    }

    public Command() {
        this.exitStatus = -1;
    }

    public boolean isSuccessful() {
        return this.exitStatus == 0;
    }

    public String toString() {
        return this.input.split(" ")[0];
    }
}

