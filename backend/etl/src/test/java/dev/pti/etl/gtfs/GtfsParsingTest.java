package dev.pti.etl.gtfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import tools.jackson.databind.node.ObjectNode;

class GtfsParsingTest {

    private static GtfsRow row(String file, String... keysAndValues) {
        Map<String, String> fields = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            fields.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return new GtfsRow(file, 7, fields);
    }

    @Test
    void rowConversionsFollowDoc13() {
        GtfsRow row = row(
                "x.txt",
                "text",
                " a ",
                "empty",
                "",
                "n",
                "12",
                "d",
                "1.5",
                "date",
                "20260929",
                "time",
                "25:10:00",
                "flag",
                "1",
                "off",
                "0",
                "tp",
                "",
                "yes",
                "True",
                "no",
                "false",
                "color",
                "00539f");
        assertThat(row.text("text")).isEqualTo("a");
        assertThat(row.text("empty")).isNull();
        assertThat(row.text("missing")).isNull();
        assertThat(row.integer("n")).isEqualTo(12);
        assertThat(row.integer("empty")).isNull();
        assertThat(row.requiredInt("n")).isEqualTo(12);
        assertThat(row.intOrDefault("empty", 3)).isEqualTo(3);
        assertThat(row.decimal("d")).isEqualTo(1.5);
        assertThat(row.decimal("empty")).isNull();
        assertThat(row.requiredDecimal("d")).isEqualTo(1.5);
        assertThat(row.date("date")).isEqualTo(LocalDate.parse("2026-09-29"));
        assertThat(row.seconds("time")).isEqualTo(90_600);
        assertThat(row.flag("flag")).isTrue();
        assertThat(row.flag("off")).isFalse();
        assertThat(row.timepoint("tp")).isTrue();
        assertThat(row.timepoint("off")).isFalse();
        assertThat(row.timepoint("flag")).isTrue();
        assertThat(row.bool("yes")).isTrue();
        assertThat(row.bool("no")).isFalse();
        assertThat(row.bool("empty")).isNull();
        assertThat(row.color("color")).isEqualTo("00539F");
        assertThat(row.color("empty")).isNull();
        assertThat(row.blank()).isFalse();
        assertThat(row("x.txt", "a", " ").blank()).isTrue();
    }

    @Test
    void rowConversionErrorsNameFileLineAndColumn() {
        GtfsRow row = row(
                "stop_times.txt",
                "n",
                "x",
                "time",
                "25:61:00",
                "date",
                "20261341",
                "flag",
                "2",
                "color",
                "blue",
                "yes",
                "maybe");
        assertThatThrownBy(() -> row.required("missing"))
                .isInstanceOf(GtfsRowException.class)
                .hasMessage("stop_times.txt line 7: missing is required");
        assertThatThrownBy(() -> row.integer("n")).hasMessageContaining("n is not an integer");
        assertThatThrownBy(() -> row.requiredInt("missing")).hasMessageContaining("missing is required");
        assertThatThrownBy(() -> row.decimal("n")).hasMessageContaining("n is not a number");
        assertThatThrownBy(() -> row.requiredDecimal("missing")).hasMessageContaining("is required");
        assertThatThrownBy(() -> row.seconds("time")).hasMessageContaining("Not a GTFS time");
        assertThatThrownBy(() -> row.date("date")).hasMessageContaining("Not a GTFS date");
        assertThatThrownBy(() -> row.flag("flag")).hasMessageContaining("not 0 or 1");
        assertThatThrownBy(() -> row.timepoint("flag")).hasMessageContaining("not 0 or 1");
        assertThatThrownBy(() -> row.color("color")).hasMessageContaining("not a hex color");
        assertThatThrownBy(() -> row.bool("yes")).hasMessageContaining("not True or False");
        assertThat(new GtfsRowException("x").ruleId()).isEqualTo("GV-04");
    }

    @Test
    void tablesMapRowsToTheirColumns() {
        GtfsInsert route = GtfsTable.ROUTES.map(
                row(
                        "routes.txt",
                        "route_id",
                        "901",
                        "route_type",
                        "0",
                        "route_long_name",
                        "Blue Line",
                        "route_color",
                        "00539f"),
                5L);
        assertThat(route.params().getValue("display_name")).isEqualTo("Blue Line");
        assertThat(route.params().getValue("agency_id")).isEqualTo("");
        assertThat(route.params().getValue("route_color")).isEqualTo("00539F");
        assertThat(route.params().getValue("feed_version_id")).isEqualTo(5L);
        assertThat(route.line()).isEqualTo(7);
        assertThat(GtfsTable.ROUTES
                        .map(row("routes.txt", "route_id", "5", "route_type", "3"), 1L)
                        .params()
                        .getValue("display_name"))
                .isEqualTo("5");

        GtfsInsert stopTime = GtfsTable.STOP_TIMES.map(
                row(
                        "stop_times.txt",
                        "trip_id",
                        "T",
                        "arrival_time",
                        "16:16:00",
                        "departure_time",
                        "16:17:00",
                        "stop_id",
                        "S",
                        "stop_sequence",
                        "3",
                        "timepoint",
                        "0"),
                5L);
        assertThat(stopTime.params().getValue("arrival_seconds")).isEqualTo(58_560);
        assertThat(stopTime.params().getValue("pickup_type")).isEqualTo(0);
        assertThat(stopTime.params().getValue("timepoint")).isEqualTo(false);

        for (GtfsTable table : GtfsTable.values()) {
            assertThat(table.baseName()).isEqualTo(table.file().replace(".txt", ""));
            assertThat(table.chunkSize()).isPositive();
            table.requiredColumns().forEach(c -> assertThat(table.isMapped(c)).isTrue());
        }
        assertThat(GtfsTable.TRIPS.isMapped("branch_letter")).isFalse();
        assertThat(GtfsTable.byFile("levels.txt")).isEmpty();
        assertThat(GtfsTable.byFile("trips.txt")).contains(GtfsTable.TRIPS);
        assertThatThrownBy(GtfsTable.VEHICLES::insertSql).isInstanceOf(IllegalStateException.class);
        assertThat(GtfsTable.VEHICLES
                        .map(row("vehicles.txt", "vehicle_id", "2050", "low_floor", "True"), 0L)
                        .params()
                        .getValue("low_floor"))
                .isEqualTo(true);
        assertThat(GtfsTable.FEED_INFO
                        .map(row("feed_info.txt", "feed_publisher_name", "MT"), 0L)
                        .params()
                        .getValue("publisher_name"))
                .isEqualTo("MT");
        for (GtfsTable t : List.of(
                GtfsTable.AGENCY,
                GtfsTable.STOPS,
                GtfsTable.CALENDAR,
                GtfsTable.CALENDAR_DATES,
                GtfsTable.SHAPES,
                GtfsTable.TRIPS)) {
            assertThat(t.insertSql()).startsWith("INSERT INTO dw.");
        }
    }

    @Test
    void mappingsOfTheOtherFiles() {
        assertThat(GtfsTable.AGENCY
                        .map(row("agency.txt", "agency_name", "MT", "agency_timezone", "America/Chicago"), 1L)
                        .params()
                        .getValue("agency_timezone"))
                .isEqualTo("America/Chicago");
        assertThat(GtfsTable.STOPS
                        .map(row("stops.txt", "stop_id", "S", "stop_lat", "44.9", "stop_lon", "-93.2"), 1L)
                        .params()
                        .getValue("location_type"))
                .isEqualTo(0);
        assertThat(GtfsTable.CALENDAR
                        .map(
                                row(
                                        "calendar.txt",
                                        "service_id",
                                        "1",
                                        "monday",
                                        "1",
                                        "tuesday",
                                        "1",
                                        "wednesday",
                                        "1",
                                        "thursday",
                                        "1",
                                        "friday",
                                        "1",
                                        "saturday",
                                        "0",
                                        "sunday",
                                        "0",
                                        "start_date",
                                        "20260928",
                                        "end_date",
                                        "20261113"),
                                1L)
                        .params()
                        .getValue("saturday"))
                .isEqualTo(false);
        assertThat(GtfsTable.CALENDAR_DATES
                        .map(
                                row("calendar_dates.txt", "service_id", "1", "date", "20261126", "exception_type", "2"),
                                1L)
                        .params()
                        .getValue("exception_type"))
                .isEqualTo(2);
        assertThat(GtfsTable.SHAPES
                        .map(
                                row(
                                        "shapes.txt",
                                        "shape_id",
                                        "A",
                                        "shape_pt_lat",
                                        "1",
                                        "shape_pt_lon",
                                        "2",
                                        "shape_pt_sequence",
                                        "3"),
                                1L)
                        .params()
                        .getValue("shape_pt_sequence"))
                .isEqualTo(3);
        assertThat(GtfsTable.TRIPS
                        .map(
                                row(
                                        "trips.txt",
                                        "route_id",
                                        "18",
                                        "service_id",
                                        "1",
                                        "trip_id",
                                        "T",
                                        "direction_id",
                                        "1",
                                        "direction",
                                        "SB"),
                                1L)
                        .params()
                        .getValue("direction_label"))
                .isEqualTo("SB");
    }

    @Test
    void issuesCountKeepSamplesAndSurviveJson() {
        GtfsIssues issues = new GtfsIssues(2);
        for (int i = 0; i < 3; i++) {
            ObjectNode sample = GtfsIssues.sample();
            sample.put("line", i);
            issues.add(FeedCheck.GV_04, sample);
        }
        issues.add(FeedCheck.GV_14, 0, List.of());
        issues.add(FeedCheck.GV_14, 1, List.of(GtfsIssues.sample().put("file", "levels.txt")));

        GtfsIssues copy = GtfsIssues.fromJson(issues.toJson(), 2);

        assertThat(copy.count(FeedCheck.GV_04)).isEqualTo(3);
        assertThat(copy.hasErrors()).isTrue();
        assertThat(copy.checks()).containsExactly(FeedCheck.GV_04, FeedCheck.GV_14);
        assertThat(copy.entries(true).get(0).path("samples")).hasSize(2);
        assertThat(copy.entries(false).get(0).path("check").asString()).isEqualTo("GV-14");
        assertThat(GtfsIssues.fromJson("", 2).hasErrors()).isFalse();
        GtfsIssues merged = new GtfsIssues(5);
        merged.addAll(copy);
        assertThat(merged.count(FeedCheck.GV_04)).isEqualTo(3);
        assertThat(FeedCheck.GV_07.code()).isEqualTo("GV-07");
        assertThat(FeedCheck.GV_11.error()).isFalse();
        assertThat(FeedCheck.sqlChecks()).hasSize(10);
    }

    @Test
    void theReportHasTheDoc21Shape() {
        GtfsIssues issues = new GtfsIssues(2);
        issues.add(FeedCheck.GV_14, GtfsIssues.sample().put("file", "levels.txt"));
        String json = FeedReport.build(
                issues,
                Map.of("routes", 2L),
                FeedReport.extraColumnsJson(Map.of("trips.txt", List.of("branch_letter"))),
                Map.of("fetch", 10L));
        assertThat(json)
                .contains("\"report_version\":1", "\"result\":\"ACCEPTED\"", "\"routes\":2", "\"branch_letter\"")
                .contains("\"fetch\":10", "\"errors\":[]");
        assertThat(FeedReport.build(new GtfsIssues(1), Map.of(), "", Map.of())).contains("\"extra_columns\":{}");
    }

    @Test
    void theHeaderIsSplitLikeTheRows() {
        assertThat(GtfsFileReader.columns("﻿trip_id, \"arrival_time\",stop_id"))
                .containsExactly("trip_id", "arrival_time", "stop_id");
        GtfsFileReader.QuotedRecordSeparatorPolicy policy = new GtfsFileReader.QuotedRecordSeparatorPolicy();
        assertThat(policy.isEndOfRecord("a,\"b")).isFalse();
        assertThat(policy.preProcess("a,\"b")).isEqualTo("a,\"b\n");
        assertThat(policy.isEndOfRecord("a,\"b\nc\"")).isTrue();
        assertThat(policy.preProcess("a,b")).isEqualTo("a,b");
        assertThat(policy.postProcess("a")).isEqualTo("a");
    }

    @Test
    void statementsAreSplitAtLineEndingSemicolons() {
        List<String> statements = FeedStatements.split("""
                -- header; with a semicolon inside
                UPDATE a SET x = 1;

                -- (2) second
                UPDATE b SET y = 2;  -- trailing comment
                """);
        assertThat(statements).hasSize(2);
        assertThat(statements.get(1)).endsWith("UPDATE b SET y = 2");
    }

    @Test
    void skipPolicySkipsRowErrorsUpToTheLimit() {
        GtfsSkipPolicy policy = new GtfsSkipPolicy(new dev.pti.common.error.ErrorClassifier(), 2);
        assertThat(policy.shouldSkip(new GtfsRowException("bad"), 0)).isTrue();
        assertThat(policy.shouldSkip(new FlatFileParseException("bad", "x", 3), 1))
                .isTrue();
        assertThat(policy.shouldSkip(new org.springframework.dao.DataIntegrityViolationException("23505"), 1))
                .isTrue();
        assertThat(policy.shouldSkip(new IllegalStateException("bug"), 0)).isFalse();
        assertThatThrownBy(() -> policy.shouldSkip(new GtfsRowException("bad"), 2))
                .hasMessageContaining("More than 2 row errors");
    }
}
