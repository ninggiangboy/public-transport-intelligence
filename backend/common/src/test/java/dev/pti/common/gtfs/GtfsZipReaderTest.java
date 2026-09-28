package dev.pti.common.gtfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.testing.GtfsFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GtfsZipReaderTest {

    @Test
    void readsTheMiniFeed(@TempDir Path dir) {
        try (GtfsZipReader reader = new GtfsZipReader(GtfsFixtures.miniFeedZip(dir.resolve("mini.zip")))) {
            List<String> routes = new ArrayList<>();
            List<String> header = reader.read("routes.txt", row -> routes.add(row.require("route_id")));

            assertThat(header).startsWith("route_id", "agency_id");
            assertThat(routes).containsExactlyInAnyOrder("18", "901");
            assertThat(reader.has("frequencies.txt")).isFalse();
            assertThatThrownBy(() -> reader.read("frequencies.txt", row -> {}))
                    .isInstanceOf(GtfsFormatException.class)
                    .hasMessage("Missing frequencies.txt");
        }
    }

    @Test
    void rejectsFilesThatAreNotZips(@TempDir Path dir) throws Exception {
        Path notZip = Files.writeString(dir.resolve("feed.zip"), "not a zip");

        assertThatThrownBy(() -> new GtfsZipReader(notZip))
                .isInstanceOf(GtfsFormatException.class)
                .hasMessageStartingWith("Cannot open GTFS zip");
    }
}
