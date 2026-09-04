package com.ss.api.shell;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Shell {
    private static final String SU_COMMAND = "su";
    private static final String ANDROID_SHELL = "sh";
    private static final String[] PATHS = new String[]{"/system/bin", "/system/xbin", "/sbin", "/vendor/bin", "/system/sbin", "/system/bin/failsafe/", "/data/local/"};
    private static final String COMMAND_END = "__END_SHELL_COMMAND";
    private static final int OTHER_EXIT_STATUS = -1337;
    protected ProcessBuilder builder;
    protected Process process;
    public BufferedReader stdout;
    public BufferedWriter stdin;

    public Shell() {
        this("sh");
    }

    public Shell(String interpreter) {
        this.builder = new ProcessBuilder(new String[]{interpreter});

        try {
            this.builder.redirectErrorStream(true);
            this.process = this.builder.start();
            this.stdout = new BufferedReader(new InputStreamReader(this.process.getInputStream()));
            this.stdin = new BufferedWriter(new OutputStreamWriter(this.process.getOutputStream()));
            String oldPath = this.exec("echo \"$PATH\"").output[0];
            ALog.d("Original shell path: " + oldPath);
            StringBuilder newPath = new StringBuilder();
            String[] var4 = PATHS;
            int var5 = var4.length;

            for(int var6 = 0; var6 < var5; ++var6) {
                String path = var4[var6];
                if (!oldPath.contains(path)) {
                    ALog.d("Adding new path " + path);
                    newPath.append(':').append(path);
                }
            }

            this.exec("PATH=$PATH" + newPath);
        } catch (IOException var8) {
            this.handleWriteOnClosedShell(var8);
        }

    }

    private void handleWriteOnClosedShell(IOException ioe) {
        ALog.e("Attempted to write on a closed shell", ioe);
        this.close();
    }

    private Command writeCommand(Command command, boolean log) {
        if (command == null) {
            throw new NullPointerException("Cannot execute a null command");
        } else {
            try {
                this.stdin.write(command.input + "\n");
                this.stdin.write("echo \"__END_SHELL_COMMAND $?\"\n");
                this.stdin.flush();
                ArrayList<String> output = new ArrayList();
                String line = null;

                while((line = this.stdout.readLine()) != null && !line.startsWith("__END_SHELL_COMMAND")) {
                    output.add(line);
                }

                command.output = (String[])((String[])output.toArray(new String[output.size()]));

                try {
                    command.exitStatus = Integer.parseInt(line.split(" ")[1]);
                } catch (ArrayIndexOutOfBoundsException var6) {
                    ALog.w("Command returned an empty exit status");
                    command.exitStatus = 0;
                } catch (Exception var7) {
                    command.exitStatus = -1337;
                }
            } catch (IOException var8) {
                this.handleWriteOnClosedShell(var8);
            }

            return command;
        }
    }

    private Command exec(String command) {
        return this.writeCommand(new Command(command), false);
    }

    public Command execute(Command command) {
        return this.writeCommand(command, true);
    }

    public Command[] execute(Command[] commands) {
        Command[] var2 = commands;
        int var3 = commands.length;

        for(int var4 = 0; var4 < var3; ++var4) {
            Command c = var2[var4];
            this.writeCommand(c, true);
        }

        return commands;
    }

    public Command execute(String command) {
        return this.writeCommand(new Command(command), true);
    }

    public Command[] execute(String[] commands) {
        Command[] out = new Command[commands.length];

        for(int i = 0; i < commands.length; ++i) {
            out[i] = this.writeCommand(new Command(commands[i]), true);
        }

        return out;
    }

    public Command[] tryExecute(Shell.Error.ErrorHandler errorHandler, String[] commands) {
        Command[] out = new Command[commands.length];

        for(int i = 0; i < commands.length; ++i) {
            out[i] = this.writeCommand(new Command(commands[i]), true);
            if (errorHandler != null) {
                boolean shouldContinue = errorHandler.onError(out[i], i);
                if (!shouldContinue) {
                    break;
                }
            }
        }

        return out;
    }

    public Command[] tryExecute(Shell.Error.ErrorHandler errorHandler, Command[] commands) {
        for(int i = 0; i < commands.length; ++i) {
            this.writeCommand(commands[i], true);
            if (errorHandler != null) {
                boolean shouldContinue = errorHandler.onError(commands[i], i);
                if (!shouldContinue) {
                    break;
                }
            }
        }

        return commands;
    }

    public Shell getRoot() {
        if (!this.isRootShell()) {
            ALog.i("Getting root");
            Command c = this.execute("fsu");
            ALog.v("Su command exit value:" + c.exitStatus);
            if (this.isRootShell()) {
                ALog.i("Got root");
            } else {
                ALog.w("Couldn't get root");
            }
        } else {
            ALog.w("Attempted to get root on this shell, but it was already root.");
        }

        return this;
    }

    public boolean isRootShell() {
        return this.getUID() == 0;
    }

    public int getUID() {
        String idOutput = this.exec("id").output[0];
        Matcher match = Pattern.compile("uid=([0-9]*)").matcher(idOutput);
        if (match.find()) {
            String uid = match.group(1);
            return Integer.parseInt(uid);
        } else {
            return -1;
        }
    }

    public void close() {
        try {
            this.stdin.write("\n");

            for(int i = 0; i < 5; ++i) {
                this.stdin.write("exit\n");
            }

            this.stdout.close();
            this.stdin.close();
            this.process.destroy();
        } catch (IOException var2) {
            ALog.w("IOException closing shell:", var2);
        }

    }

    public abstract static class Error {
        public static final Shell.Error.ErrorHandler DEFAULT_HANDLER = new Shell.Error.ErrorHandler() {
            public boolean onError(Command c, int index) {
                if (c.exitStatus == 0) {
                    return true;
                } else {
                    ALog.w("Stopping execution, exitStatus:" + c.exitStatus);
                    return false;
                }
            }
        };

        public Error() {
        }

        public interface ErrorHandler {
            boolean onError(Command var1, int var2);
        }
    }
}
