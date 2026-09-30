package dev.pti.api.platform.adapter.in.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebhookTokenTest {

    @Test
    @DisplayName("The token is the trimmed content of the file, compared exactly")
    void readsAndMatches(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("token"), "abc123\n");
        WebhookToken token = new WebhookToken(file);

        token.reload();

        assertThat(token.matches("abc123")).isTrue();
        assertThat(token.matches("abc12")).isFalse();
        assertThat(token.matches("abc1234")).isFalse();
        assertThat(token.matches("ABC123")).isFalse();
        assertThat(token.matches(null)).isFalse();
    }

    @Test
    @DisplayName("A missing or empty file means no token is set: nothing matches, not even an empty token")
    void unsetToken(@TempDir Path dir) throws IOException {
        WebhookToken missing = new WebhookToken(dir.resolve("absent"));
        missing.reload();
        WebhookToken empty = new WebhookToken(Files.writeString(dir.resolve("empty"), "  \n"));
        empty.reload();

        assertThat(missing.matches("")).isFalse();
        assertThat(missing.matches("x")).isFalse();
        assertThat(empty.matches("")).isFalse();
    }

    @Test
    @DisplayName("RB-12 a rotated token is picked up without a restart")
    void reloadsWhenTheFileChanges(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("token"), "first");
        try (WebhookToken token = new WebhookToken(file)) {
            token.start();
            assertThat(token.matches("first")).isTrue();

            Files.writeString(file, "second");

            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> assertThat(token.matches("second")).isTrue());
            assertThat(token.matches("first")).isFalse();
        }
    }

    @Test
    void aTokenFileCreatedLaterIsPickedUp(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("late-token");
        try (WebhookToken token = new WebhookToken(file)) {
            token.start();
            assertThat(token.matches("later")).isFalse();

            Files.writeString(file, "later");

            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> assertThat(token.matches("later")).isTrue());
        }
    }
}
