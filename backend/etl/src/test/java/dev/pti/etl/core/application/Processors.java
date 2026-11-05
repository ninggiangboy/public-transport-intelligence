package dev.pti.etl.core.application;

import dev.pti.common.json.MessageSchemas;
import dev.pti.etl.core.adapter.out.json.EnvelopeReader;
import dev.pti.etl.rules.domain.RealtimeRules;
import dev.pti.etl.rules.domain.RuleEngine;
import dev.pti.etl.testing.EtlFixtures;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/** The GTFS-realtime processors wired as in the application, with the default DQ settings. */
final class Processors {

    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    static final EnvelopeReader READER = new EnvelopeReader(MessageSchemas.load(), VALIDATOR);

    private Processors() {}

    static VehiclePositionProcessor vehiclePositions() {
        return new VehiclePositionProcessor(
                READER, new RuleEngine<>(RealtimeRules.all(EtlFixtures.dq()), EtlFixtures.dq()));
    }

    static TripUpdateProcessor tripUpdates() {
        return new TripUpdateProcessor(READER, new RuleEngine<>(RealtimeRules.all(EtlFixtures.dq()), EtlFixtures.dq()));
    }
}
