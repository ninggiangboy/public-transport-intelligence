package dev.pti.api.platform.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProblemTypeTest {

    private static final Pattern DOC_ROW = Pattern.compile("^\\| `([a-z-]+)` \\| (\\d{3}) \\| ([^|]+?) \\|");

    @Test
    @DisplayName("Slugs are unique and the URN is urn:pti:problem:<slug>")
    void slugsAreUniqueUrns() {
        List<String> slugs = java.util.Arrays.stream(ProblemType.values())
                .map(ProblemType::slug)
                .toList();

        assertThat(slugs).doesNotHaveDuplicates();
        assertThat(ProblemType.REPLAY_ALREADY_RUNNING.urn()).isEqualTo("urn:pti:problem:replay-already-running");
    }

    @Test
    void fromSlugFindsATypeOrNothing() {
        assertThat(ProblemType.fromSlug("rate-limited")).contains(ProblemType.RATE_LIMITED);
        assertThat(ProblemType.fromSlug("no-such-slug")).isEmpty();
    }

    @Test
    @DisplayName("The enum matches the table of DOC-30 §3.2: slug, status and title")
    void matchesTheCatalogOfTheDesignDocument() throws IOException {
        Path doc = Path.of(System.getProperty("pti.repo-root"), "docs/06-design/error-handling.md");
        Map<String, String> documented = new LinkedHashMap<>();
        for (String line : Files.readAllLines(doc)) {
            Matcher row = DOC_ROW.matcher(line);
            if (row.find()) {
                documented.put(row.group(1), row.group(2) + " " + row.group(3));
            }
        }

        Map<String, String> implemented = new LinkedHashMap<>();
        for (ProblemType type : ProblemType.values()) {
            implemented.put(type.slug(), type.status() + " " + type.title());
        }

        assertThat(implemented).isEqualTo(documented);
    }

    @Test
    void apiExceptionDefaultsCarryNoExtras() {
        ApiException exception = new ApiException("Nothing here") {
            private static final long serialVersionUID = 1L;

            @Override
            public ProblemType type() {
                return ProblemType.NOT_FOUND;
            }
        };

        assertThat(exception.getMessage()).isEqualTo("Nothing here");
        assertThat(exception.errors()).isEmpty();
        assertThat(exception.extensions()).isEmpty();
        assertThat(exception.retryAfterSeconds()).isNull();
        assertThat(exception.getStackTrace()).isEmpty();
    }
}
