package dev.pti.api.platform.adapter.in.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Alertmanager webhook token (DOC-27 §6): the content of {@code pti.api.alert-webhook.token-file}, read when the
 * application starts and again whenever the directory of the file changes, so that rotating the token (RB-12) needs
 * no restart. Kubernetes and compose replace secrets by swapping links in the directory, which is why the directory
 * is watched, not the file.
 *
 * <p>A missing or empty file is not fatal: the token is then unset and every webhook call is refused.
 */
public final class WebhookToken implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WebhookToken.class);

    private final Path file;
    private volatile byte[] token = new byte[0];
    private volatile @Nullable WatchService watcher;
    private volatile @Nullable Thread thread;

    public WebhookToken(Path file) {
        this.file = file;
    }

    /** Reads the file and starts watching its directory. */
    public void start() {
        reload();
        Path directory = file.toAbsolutePath().getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            log.warn("Webhook token directory does not exist, so the token will not be reloaded: {}", file);
            return;
        }
        try {
            WatchService service = FileSystems.getDefault().newWatchService();
            directory.register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            watcher = service;
            Thread watcherThread =
                    Thread.ofPlatform().name("webhook-token-watcher").daemon().start(() -> watch(service));
            thread = watcherThread;
        } catch (IOException e) {
            log.warn("Cannot watch the webhook token directory, so the token will not be reloaded", e);
        }
    }

    /** Reads the token file now. */
    public void reload() {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).strip();
            token = content.getBytes(StandardCharsets.UTF_8);
            if (content.isEmpty()) {
                log.warn("Webhook token file is empty, so /internal/** refuses every request");
            } else {
                log.info("Webhook token loaded");
            }
        } catch (IOException e) {
            token = new byte[0];
            log.warn("Webhook token file cannot be read, so /internal/** refuses every request: {}", file);
        }
    }

    /** Constant-time comparison (DOC-27 §6); false while no token is set. */
    public boolean matches(@Nullable String presented) {
        byte[] expected = token;
        if (presented == null || expected.length == 0) {
            return false;
        }
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected);
    }

    private void watch(WatchService service) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = service.take();
                key.pollEvents();
                reload();
                if (!key.reset()) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException e) {
            log.debug("Webhook token watcher closed");
        }
    }

    @Override
    public void close() throws IOException {
        Thread watcherThread = thread;
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
        WatchService service = watcher;
        if (service != null) {
            service.close();
        }
    }
}
