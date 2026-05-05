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

    private static final String MODEL = "qwen2.5-coder:1.5b";
    private static final String EMBED_MODEL = "nomic-embed-text";
    private static final String REPOSITORY = "dashaun/testcontainer-ollama-qwen2.5-coder_1.5b";

    @ShellMethod(key = "generate", value = "Pull qwen2.5-coder into an Ollama container and commit it as a tagged image")
    public void generate(@ShellOption(value = "--ollamaVersion") String ollamaVersion) throws Exception {
        configureDockerHost();

        String baseImage  = "ollama/ollama:" + ollamaVersion;
        String versionTag = REPOSITORY + ":" + ollamaVersion;
        String latestTag  = REPOSITORY + ":latest";

        System.out.println("Starting OllamaContainer: " + baseImage);
        try (OllamaContainer ollama = new OllamaContainer(baseImage)) {
            ollama.start();
            System.out.println("Pulling model: " + MODEL);
            ollama.execInContainer("ollama", "pull", MODEL);
            System.out.println("Pulling model: " + EMBED_MODEL);
            ollama.execInContainer("ollama", "pull", EMBED_MODEL);
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

    void configureDockerHost() {
        wakeDockerDesktop();
        cleanDockerHostProperties();
        resetDockerSingletons();
    }

    void wakeDockerDesktop() {
        // Docker Desktop Resource Saver pauses the VM; the proxy socket accepts connections and responds
        // to /_ping immediately, but /info returns empty 400 until the VM resumes. Poll /info for a
        // non-empty ServerVersion to confirm the engine itself is ready.
        Path sock = resolveDockerSocket();
        System.out.println("Waiting for Docker engine to be ready...");
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                var proc = new ProcessBuilder(
                        "curl", "--silent",
                        "--unix-socket", sock.toString(),
                        "http://localhost/v1.41/info")
                    .redirectErrorStream(true)
                    .start();
                String output = new String(proc.getInputStream().readAllBytes()).trim();
                proc.waitFor();
                if (output.contains("\"ServerVersion\":\"") && !output.contains("\"ServerVersion\":\"\"")) {
                    System.out.println("Docker engine ready.");
                    return;
                }
            } catch (Exception e) {
                System.err.println("docker info check failed: " + e.getMessage());
            }
            try { Thread.sleep(2_000); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        System.err.println("Warning: Docker engine did not become ready within 60 seconds");
    }

    private void cleanDockerHostProperties() {
        // Remove stale strategy overrides so Testcontainers auto-detects via DOCKER_HOST env var.
        try {
            Path propsPath = Path.of(System.getProperty("user.home"), ".testcontainers.properties");
            Properties props = new Properties();
            if (Files.exists(propsPath)) {
                try (InputStream in = Files.newInputStream(propsPath)) { props.load(in); }
            }
            props.remove("docker.client.strategy");
            props.remove("DOCKER_HOST");
            props.remove("tc.host");
            try (OutputStream out = Files.newOutputStream(propsPath)) {
                props.store(out, "Modified by testcontainer-ollama-qwen2.5-coder");
            }
        } catch (Exception e) {
            System.err.println("Warning: could not clean docker host properties: " + e.getMessage());
        }
    }

    private void resetDockerSingletons() {
        // Docker Desktop 4.x requires API >= 1.40; Testcontainers' shaded docker-java defaults
        // to 1.32 and gets an empty 400 from Docker Desktop's proxy for any version below 1.40.
        // The shaded DefaultDockerClientConfig reads the API version from the system property "api.version".
        System.setProperty("api.version", "1.41");
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

    private Path resolveDockerSocket() {
        String dockerHost = System.getenv("DOCKER_HOST");
        if (dockerHost != null && dockerHost.startsWith("unix://")) {
            return Path.of(dockerHost.substring("unix://".length()));
        }
        Path macSocket = Path.of(System.getProperty("user.home"), ".docker", "run", "docker.sock");
        return Files.exists(macSocket) ? macSocket : Path.of("/var/run/docker.sock");
    }
}
