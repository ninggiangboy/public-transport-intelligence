package dev.pti.api.platform.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.pti.api.platform.domain.ApiException;
import dev.pti.api.platform.domain.ProblemType;
import dev.pti.common.error.ErrorClassifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The advice on its own (DOC-30 §3.3), with the catalog of types that only later slices throw. */
class ApiExceptionHandlerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private MockMvc mvc;

    /** A type that carries everything an ApiException may: fields, extension members and Retry-After. */
    static final class Busy extends ApiException {
        private static final long serialVersionUID = 1L;

        Busy() {
            super("A replay for GTFS_RT_VEHICLE_POSITION is already pending or running.");
        }

        @Override
        public ProblemType type() {
            return ProblemType.REPLAY_ALREADY_RUNNING;
        }

        @Override
        public Map<String, Object> extensions() {
            return Map.of("existingReplayId", "0192f5a1-2b3c-7d4e-8f90-a1b2c3d4e5f6");
        }

        @Override
        public Integer retryAfterSeconds() {
            return 9;
        }
    }

    @RestController
    static class Controller {

        @GetMapping("/busy")
        String busy() {
            throw new Busy();
        }

        @GetMapping("/denied")
        String denied() {
            throw new AccessDeniedException("no");
        }

        @GetMapping("/constraint")
        String constraint(@RequestParam(required = false) String x) {
            throw new ConstraintViolationException("bad", java.util.Set.of());
        }
    }

    @BeforeEach
    void setUp() {
        ApiExceptionHandler advice = new ApiExceptionHandler(new ProblemFactory(registry), new ErrorClassifier());
        mvc = MockMvcBuilders.standaloneSetup(new Controller())
                .setControllerAdvice(advice)
                .addInterceptors(new UnknownQueryParameterInterceptor())
                .build();
    }

    @Test
    @DisplayName("An ApiException keeps its type, extension members and Retry-After (DOC-30 §3.1)")
    void apiExceptionCarriesEverything() throws Exception {
        mvc.perform(get("/busy"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "9"))
                .andExpect(jsonPath("$.type").value("urn:pti:problem:replay-already-running"))
                .andExpect(jsonPath("$.title").value("Replay already running"))
                .andExpect(jsonPath("$.existingReplayId").value("0192f5a1-2b3c-7d4e-8f90-a1b2c3d4e5f6"))
                .andExpect(jsonPath("$.instance").value("/busy"));
    }

    @Test
    @DisplayName("A method the controller does not offer is 405 with the Allow header")
    void methodNotAllowed() throws Exception {
        mvc.perform(delete("/busy"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.type").value("urn:pti:problem:method-not-allowed"));
    }

    @Test
    @DisplayName("An AccessDeniedException thrown by a controller is 403 forbidden")
    void accessDenied() throws Exception {
        mvc.perform(get("/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:forbidden"));
    }

    @Test
    @DisplayName("A ConstraintViolationException is 400 validation-error")
    void constraintViolation() throws Exception {
        mvc.perform(get("/constraint"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:pti:problem:validation-error"));
    }

    @Test
    @DisplayName("The interceptor names the unknown parameters, sorted, and lets declared ones through")
    void unknownParametersAreListed() throws Exception {
        mvc.perform(get("/constraint").param("zeta", "1").param("alpha", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("alpha"))
                .andExpect(jsonPath("$.errors[1].field").value("zeta"));
    }

    @Test
    @DisplayName("pti_api_problems_total counts every problem by slug and status")
    void problemsAreCounted() throws Exception {
        mvc.perform(get("/busy"));
        mvc.perform(get("/busy"));
        mvc.perform(get("/denied"));

        assertThat(registry.get("pti.api.problems")
                        .tag("type", "replay-already-running")
                        .tag("status", "409")
                        .counter()
                        .count())
                .isEqualTo(2.0);
        assertThat(registry.get("pti.api.problems")
                        .tag("type", "forbidden")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("The standard detail of each type is a sentence, never empty")
    void everyTypeHasADetail() {
        for (ProblemType type : ProblemType.values()) {
            assertThat(ProblemFactory.defaultDetail(type))
                    .as(type.slug())
                    .isNotBlank()
                    .endsWith(".");
        }
        assertThat(List.of(ProblemType.values())).hasSize(26);
    }
}
