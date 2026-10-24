package com.alexastudillo.companyregistry;

import com.alexastudillo.companyregistry.contract.DeliveryEnvironmentResource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class DeliveryProcess implements AutoCloseable {
    final Process process;
    final String base;
    final Path log;

    DeliveryProcess(Path work, Map<String, String> overrides) throws Exception {
        Files.createDirectories(work);
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        base = "http://127.0.0.1:" + port;
        var artifact = Path.of("build").toAbsolutePath();
        var command = new ArrayList<String>();
        boolean nativeArtifact = Boolean.getBoolean("native.artifact");
        if (nativeArtifact) {
            var binary = artifact.resolve("company-registry-service-1.0.0-SNAPSHOT-runner");
            try (var input = Files.newInputStream(binary)) {
                if (!java.util.Arrays.equals(new byte[]{127, 'E', 'L', 'F'}, input.readNBytes(4)))
                    throw new IllegalStateException("Expected real ELF executable");
            }
            command.add(binary.toString());
        } else {
            command.add(Path.of(System.getProperty("java.home"), "bin/java").toString()); command.add("-Xmx256m");
        }
        var settings = new HashMap<>(DeliveryEnvironmentResource.settings);
        settings.put("company-registry.outbox.mode", "published");
        settings.put("company-registry.outbox.publisher.publish-timeout", "1s");
        settings.put("company-registry.outbox.publisher.claim-lease", "3s");
        settings.put("company-registry.outbox.publisher.initial-backoff", "100ms");
        settings.put("company-registry.outbox.publisher.maximum-backoff", "1s");
        settings.put("quarkus.http.port", Integer.toString(port));
        settings.put("quarkus.http.host", "127.0.0.1");
        settings.put("quarkus.profile", "test");
        settings.putAll(overrides);
        settings.forEach((key, value) -> command.add("-D" + key + "=" + value));
        if (!nativeArtifact) command.addAll(java.util.List.of("-jar", artifact.resolve("quarkus-app/quarkus-run.jar").toString()));
        log = work.resolve("application.log");
        process = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            try {
                org.awaitility.Awaitility.await().alias("Publisher startup: " + log).atMost(Duration.ofSeconds(60)).until(() -> {
                    if (!process.isAlive()) throw new IllegalStateException("Publisher exited; inspect " + log);
                    try { return client.send(HttpRequest.newBuilder(URI.create(base + "/q/health/ready"))
                            .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200; }
                    catch (java.io.IOException _) { return false; }
                });
            } catch (RuntimeException failure) { close(); throw failure; }
        }
    }
    void kill() throws InterruptedException {
        process.destroyForcibly();
        if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IllegalStateException("Cannot terminate test publisher");
    }
    @Override public void close() throws Exception {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) kill();
        var evidence = Path.of("build", "hu08-evidence", Boolean.getBoolean("native.artifact") ? "native" : "jvm", "processes");
        Files.createDirectories(evidence);
        Files.copy(log, evidence.resolve(log.getParent().getFileName() + "-" + process.pid() + ".log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
