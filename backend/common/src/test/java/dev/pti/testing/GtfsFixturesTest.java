package dev.pti.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GtfsFixturesTest {

    @Test
    void zipsEveryFileOfTheMiniFeed(@TempDir Path dir) throws IOException {
        Path zip = GtfsFixtures.miniFeedZip(dir.resolve("mini.zip"));

        try (ZipFile file = new ZipFile(zip.toFile())) {
            assertThat(file.stream().map(ZipEntry::getName)).containsExactlyElementsOf(GtfsFixtures.MINI_FEED_FILES);
        }
    }

    @Test
    void recordsTheSourceFeed() throws IOException {
        try (InputStream in = GtfsFixtures.open("SOURCE.txt")) {
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(source)
                    .contains("source: metrotransit-mn-20260926.zip")
                    .contains("source_sha256: 2cfc4a1b6efd7e860cbc0ee4bc90487fb8a5eb3f7946b35df021569d032ee23b")
                    .contains("trips.txt: 42 rows", "stop_times.txt: 1813 rows");
        }
    }

    /** DQ-09 and G-11 need a trip past midnight (DOC-44 §5.1). */
    @Test
    void containsATripPastMidnight() throws IOException {
        List<String> late = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(GtfsFixtures.open("stop_times.txt"), StandardCharsets.UTF_8))) {
            reader.lines()
                    .skip(1)
                    .filter(line -> line.matches("^[^,]*,(2[4-9]|3\\d):.*"))
                    .forEach(late::add);
        }

        assertThat(late).isNotEmpty();
    }

    @Test
    void rejectsUnknownFiles() {
        assertThatThrownBy(() -> GtfsFixtures.open("fare_rules.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No such file in the mini feed: fare_rules.txt");
    }
}
