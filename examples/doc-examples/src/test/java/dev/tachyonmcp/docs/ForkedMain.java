package dev.tachyonmcp.docs;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class ForkedMain implements AutoCloseable {

    public static final int DOCUMENTED_PORT = 8080;

    private final Process process;
    private final Path outputFile;

    private ForkedMain(Process process, Path outputFile) {
        this.process = process;
        this.outputFile = outputFile;
    }

    public String output() {
        try {
            return Files.readString(outputFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ForkedMain start(String mainClass) throws IOException {
        return start(mainClass, Map.of(), DOCUMENTED_PORT);
    }

    public static ForkedMain start(String mainClass, Map<String, String> env, int port) throws IOException {
        assumeTrue(portIsFree(port), "port " + port + " is in use; the page's examples bind it");
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var outputFile = Files.createTempFile("forked-main-", ".log");
        var builder = new ProcessBuilder(java, "-cp", classpath, mainClass)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile());
        builder.environment().putAll(env);
        var process = builder.start();
        var forked = new ForkedMain(process, outputFile);
        try {
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
                if (!process.isAlive()) {
                    throw new IllegalStateException(mainClass + " exited with " + process.exitValue());
                }
                return listening(port);
            });
        } catch (Throwable failure) {
            forked.close();
            throw failure;
        }
        return forked;
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                Files.deleteIfExists(outputFile);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static boolean portIsFree(int port) {
        try (var socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean listening(int port) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
