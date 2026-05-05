# testcontainer-ollama-qwen2.5-coder

A Spring Shell CLI that builds a pre-warmed [Ollama](https://ollama.com) Docker image containing the `qwen2.5-coder:1.5b` and `nomic-embed-text` models. The resulting image is published to the GitHub Container Registry so workshops and CI environments can pull a ready-to-use image instead of downloading models at runtime.

## Published image

```
ghcr.io/dashaun/testcontainer-ollama-qwen2.5-coder_1.5b:latest
```

## How it works

1. Starts an `ollama/ollama` container via [Testcontainers](https://testcontainers.com)
2. Pulls `qwen2.5-coder:1.5b` and `nomic-embed-text` into the running container
3. Commits the container as a new Docker image tagged with the Ollama version and `:latest`
4. Pushes both tags to GHCR

A GitHub Actions workflow runs daily, detects new `ollama/ollama` releases, and rebuilds automatically when a newer version is found.

## Prerequisites

- Java 17+
- Docker Desktop (Mac/Linux)
- Maven

## Running locally

```bash
mvn package -DskipTests
java -jar target/*.jar generate --ollamaVersion 0.21.0
```

To push the resulting images, log in to GHCR first:

```bash
docker login ghcr.io -u <your-github-username>
# enter a GitHub personal access token with write:packages scope when prompted
docker push ghcr.io/dashaun/testcontainer-ollama-qwen2.5-coder_1.5b:0.21.0
docker push ghcr.io/dashaun/testcontainer-ollama-qwen2.5-coder_1.5b:latest
```

## Running tests

```bash
mvn test
```

The tests verify that Docker is reachable and that an Ollama container can start and commit an image successfully.

## GitHub Actions

The workflow (`.github/workflows/update-image.yml`) runs at 06:00 UTC daily. It:

1. Fetches the latest semver tag from `ollama/ollama` on Docker Hub
2. Compares it to the latest version published to GHCR
3. Skips the build if already up to date
4. Builds and pushes both the versioned and `:latest` tags if a new version is found

You can also trigger it manually from the Actions tab with an optional `ollama_version` input to force a specific version.

No secrets are required — authentication uses the built-in `GITHUB_TOKEN`.

## Stack

| Component | Version |
|---|---|
| Spring Boot | 3.5.14 |
| Spring Shell | 3.4.2 |
| Testcontainers | 2.0.5 |
| Java | 17 (Liberica) |
