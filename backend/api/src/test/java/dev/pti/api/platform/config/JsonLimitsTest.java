package dev.pti.api.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.apitest.ApiWebTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/** DOC-27 §9: the mapper of the application reads at most 64 levels of nesting and strings of 1 MiB. */
class JsonLimitsTest extends ApiWebTestSupport {

    @Autowired
    private JsonMapper mapper;

    @Test
    @DisplayName("The application's JsonMapper carries the stream limits of DOC-27 §9")
    void streamLimits() {
        var constraints = mapper.tokenStreamFactory().streamReadConstraints();

        assertThat(constraints.getMaxNestingDepth()).isEqualTo(64);
        assertThat(constraints.getMaxStringLength()).isEqualTo(1024 * 1024);
    }
}
