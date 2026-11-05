package dev.pti.etl.core.application;

import dev.pti.common.json.MessageSchemas;
import dev.pti.etl.config.DqProperties;
import dev.pti.etl.core.adapter.out.json.JacksonBestEffortKeys;
import dev.pti.etl.core.adapter.out.json.JsonEnvelopeReader;
import dev.pti.etl.core.application.port.BestEffortKeys;
import dev.pti.etl.core.application.port.EnvelopeReader;
import dev.pti.etl.core.domain.RealtimeFacts;
import dev.pti.etl.core.domain.RuleEngine;
import dev.pti.etl.rules.domain.RealtimeRules;
import dev.pti.etl.testing.EtlFixtures;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/** The GTFS-realtime processors wired as in the application, with the default DQ settings. */
final class Processors {

    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    static final EnvelopeReader READER = new JsonEnvelopeReader(MessageSchemas.load(), VALIDATOR);
    static final BestEffortKeys KEYS = new JacksonBestEffortKeys();

    private Processors() {}

    static RuleEngine<RealtimeFacts> rules(DqProperties dq) {
        return new RuleEngine<>(RealtimeRules.all(dq.bboxMargin(), dq.maxClockSkew(), dq.maxDelay()), dq::enabled);
    }

    static VehiclePositionProcessor vehiclePositions() {
        return new VehiclePositionProcessor(READER, rules(EtlFixtures.dq()), KEYS);
    }

    static TripUpdateProcessor tripUpdates() {
        return new TripUpdateProcessor(READER, rules(EtlFixtures.dq()), KEYS);
    }
}
