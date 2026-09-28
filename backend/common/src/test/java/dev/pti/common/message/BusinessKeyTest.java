package dev.pti.common.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The examples of DOC-13 §6.2. */
class BusinessKeyTest {

    @Test
    void formatsTheVehiclePositionKeyWithMilliseconds() {
        assertThat(BusinessKey.vehiclePosition("2050", Instant.parse("2026-09-29T21:19:05Z")))
                .isEqualTo("2050|2026-09-29T21:19:05.000Z");
    }

    @Test
    void formatsTheTripUpdateKeyWithAnIsoServiceDate() {
        assertThat(BusinessKey.tripUpdate(LocalDate.of(2026, 9, 29), "1361959", 14))
                .isEqualTo("2026-09-29|1361959|14");
    }

    @Test
    void formatsTheTicketSaleKey() {
        assertThat(BusinessKey.ticketSale(
                        LocalDate.of(2026, 9, 29), UUID.fromString("3f2b8c1e-7d4a-4e51-9b0c-2a6f1d8e9c01")))
                .isEqualTo("2026-09-29|3f2b8c1e-7d4a-4e51-9b0c-2a6f1d8e9c01");
    }

    @Test
    void givesOneKeyPerVehiclePosition() {
        assertThat(BusinessKey.of(SampleMessages.vehiclePositionEnvelope()))
                .containsExactly("2050|2026-09-29T21:19:05.000Z");
    }

    @Test
    void givesOneKeyPerStopTimeUpdate() {
        assertThat(BusinessKey.of(SampleMessages.tripUpdateEnvelope()))
                .containsExactly("2026-09-29|1361959|14", "2026-09-29|1361959|15");
    }
}
