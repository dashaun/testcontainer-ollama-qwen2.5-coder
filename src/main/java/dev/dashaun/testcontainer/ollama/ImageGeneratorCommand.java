package dev.dashaun.testcontainer.ollama;

import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.utility.TestcontainersConfiguration;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@ShellComponent
public class ImageGeneratorCommand {

    private static final String MODEL = "qwen2.5-coder";
    private static final String REPOSITORY = "dashaun/testcontainer-ollama-qwen2.5-coder";

    @ShellMethod(key = "generate", value = "Pull qwen2.5-coder into an Ollama container and commit it as a tagged image")
    public void generate(@ShellOption(value = "--ollamaVersion") String ollamaVersion) throws Exception {
        System.out.println("[DIAG] generate called, code version=4");
        configureDockerHost();
        System.out.println("[DIAG] configureDockerHost done");
        System.out.println("[DIAG] strategy after reset: " + TestcontainersConfiguration.getInstance().getDockerClientStrategyClassName());
        System.out.println("[DIAG] docker.host after reset: " + TestcontainersConfiguration.getInstance().getEnvVarOrUserProperty("docker.host", null));

        String baseImage  = "ollama/ollama:" + ollamaVersion;
        String versionTag = REPOSITORY + ":" + ollamaVersion;
        String latestTag  = REPOSITORY + ":latest";

        // Direct socket reachability test — bypasses Testcontainers entirely
        try {
            var addr = java.net.UnixDomainSocketAddress.of(
                Path.of(System.getProperty("user.home"), ".docker", "run", "docker.sock"));
            var ch = java.nio.channels.SocketChannel.open(addr);
            ch.close();
            System.out.println("[DIAG] direct unix socket connect: OK");
        } catch (Exception e) {
            System.out.println("[DIAG] direct unix socket connect: FAILED - " + e);
        }

        System.out.println("Starting OllamaContainer: " + baseImage);
        try (OllamaContainer ollama = new OllamaContainer(baseImage)) {
            ollama.start();
            System.out.println("Pulling model: " + MODEL);
            ollama.execInContainer("ollama", "pull", MODEL);
            System.out.println("Committing image as: " + versionTag);
            ollama.commitToImage(versionTag);
        }

        System.out.println("Tagging as: " + latestTag);
        DockerClientFactory.instance().client()
                .tagImageCmd(versionTag, REPOSITORY, "latest")
                .exec();

        System.out.println("Done. Images ready to push:");
        System.out.println("  " + versionTag);
        System.out.println("  " + latestTag);
    }

    /**
     * Writes "docker.host" (the key EnvironmentAndSystemPropertyClientProviderStrategy reads
     * via getSetting("docker.host") → getEnvVarOrUserProperty("docker.host") →
     * userProperties.getProperty("docker.host")) into ~/.testcontainers.properties, then
     * resets the three Testcontainers singletons so the fresh value is picked up.
     *
     * Previous attempts used "DOCKER_HOST" (uppercase, no dot) — that key is never matched
     * by userProperties.getProperty("docker.host") because Java Properties is case-sensitive.
     */
    void configureDockerHost() {
        wakeDockerDesktop();
        writeDockerHostProperties();

        // Docker Desktop may need a moment after waking; retry detection until ServerVersion is non-empty.
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            resetDockerSingletons();
            try {
                var info = DockerClientFactory.instance().client().infoCmd().exec();
                if (info.getServerVersion() != null && !info.getServerVersion().isEmpty()) {
                    return;
                }
            } catch (Exception ignored) {}
            try { Thread.sleep(1_000); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException("Docker did not become ready within 30 seconds");
    }

    private void writeDockerHostProperties() {
        try {
            // docker-cli.sock is Docker Desktop's proxy: it handles Resource Saver wake-up automatically.
            // docker.sock (the raw engine socket) returns empty 400 when Docker Desktop is paused.
            // Always prefer docker-cli.sock on macOS when it exists, regardless of DOCKER_HOST.
            Path cliSock = Path.of(System.getProperty("user.home"),
                "Library", "Containers", "com.docker.docker", "Data", "docker-cli.sock");
            String dockerHost;
            if (Files.exists(cliSock)) {
                dockerHost = "unix://" + cliSock;
            } else {
                dockerHost = System.getenv("DOCKER_HOST");
                if (dockerHost == null) {
                    Path macSocket = Path.of(System.getProperty("user.home"), ".docker", "run", "docker.sock");
                    dockerHost = Files.exists(macSocket)
                        ? "unix://" + macSocket
                        : "unix:///var/run/docker.sock";
                }
            }
            Path propsPath = Path.of(System.getProperty("user.home"), ".testcontainers.properties");
            Properties props = new Properties();
            if (Files.exists(propsPath)) {
                try (InputStream in = Files.newInputStream(propsPath)) { props.load(in); }
            }
            props.remove("docker.client.strategy");
            props.remove("DOCKER_HOST");
            props.setProperty("docker.host", dockerHost);
            try (OutputStream out = Files.newOutputStream(propsPath)) {
                props.store(out, "Modified by testcontainer-ollama-qwen2.5-coder");
            }
        } catch (Exception e) {
            System.err.println("Warning: could not write docker host properties: " + e.getMessage());
        }
    }

    private void resetDockerSingletons() {
        try {
            var tcField = TestcontainersConfiguration.class.getDeclaredField("instance");
            tcField.setAccessible(true);
            ((AtomicReference<?>) tcField.get(null)).set(null);

            var dcfField = DockerClientFactory.class.getDeclaredField("instance");
            dcfField.setAccessible(true);
            dcfField.set(null, null);

            var failFast = DockerClientProviderStrategy.class.getDeclaredField("FAIL_FAST_ALWAYS");
            failFast.setAccessible(true);
            ((AtomicBoolean) failFast.get(null)).set(false);
        } catch (Exception e) {
            System.err.println("Warning: could not reset Docker singletons: " + e.getMessage());
        }
    }

    void wakeDockerDesktop() {
        System.out.println("Waiting for Docker Desktop to be ready...");
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                var proc = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}")
                    .redirectErrorStream(true)
                    .start();
                String version = new String(proc.getInputStream().readAllBytes()).trim();
                int rc = proc.waitFor();
                if (rc == 0 && !version.isEmpty() && !version.equals("<no value>")) {
                    System.out.println("Docker Desktop ready, version: " + version);
                    return;
                }
            } catch (Exception e) {
                System.err.println("docker info attempt failed: " + e.getMessage());
            }
            try { Thread.sleep(2_000); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.err.println("Warning: Docker Desktop did not respond within 60 seconds");
    }
}
