package dev.pti.common.gtfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GtfsCsvTest {

    private static List<GtfsRecord> read(String text, List<String> header) {
        List<GtfsRecord> rows = new ArrayList<>();
        header.addAll(
                GtfsCsv.read("test.txt", new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), rows::add));
        return rows;
    }

    @Test
    void readsQuotedFieldsBomAndMixedLineEnds() {
        List<String> header = new ArrayList<>();
        List<GtfsRecord> rows = read(
                "﻿stop_id,stop_name,stop_desc\r\n"
                        + "1,\"Lake St, \"\"East\"\"\",\r\n"
                        + "\n"
                        + "2,\"Two\nlines\",x\n"
                        + "3,Plain,last",
                header);

        assertThat(header).containsExactly("stop_id", "stop_name", "stop_desc");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get("stop_name")).isEqualTo("Lake St, \"East\"");
        assertThat(rows.get(0).get("stop_desc")).isNull();
        assertThat(rows.get(1).get("stop_name")).isEqualTo("Two\nlines");
        assertThat(rows.get(2).get("stop_desc")).isEqualTo("last");
        assertThat(rows.get(2).get("zone_id")).isNull();
    }

    @Test
    void parsesTypedValues() {
        GtfsRecord row = read(
                        "trip_id,arrival_time,pickup_type,shape_dist_traveled,drop_off_type\n"
                                + "t1, 25:10:00 ,,12.5,1\n",
                        new ArrayList<>())
                .getFirst();

        assertThat(row.requireSeconds("arrival_time")).isEqualTo(90_600);
        assertThat(row.getInt("pickup_type", 0)).isZero();
        assertThat(row.getInt("drop_off_type", 0)).isEqualTo(1);
        assertThat(row.requireDouble("shape_dist_traveled")).isEqualTo(12.5);
        assertThat(row.getDouble("missing")).isNaN();
    }

    @Test
    void reportsTheFileAndLineOfBadValues() {
        GtfsRecord row = read("a,b,c\n1,x,25:61:00\n", new ArrayList<>()).getFirst();

        assertThatThrownBy(() -> row.require("d")).hasMessage("test.txt line 2: missing d");
        assertThatThrownBy(() -> row.requireInt("b")).hasMessage("test.txt line 2: not an integer in b: 'x'");
        assertThatThrownBy(() -> row.requireDouble("b")).hasMessage("test.txt line 2: not a number in b: 'x'");
        assertThatThrownBy(() -> row.requireSeconds("c")).hasMessage("test.txt line 2: bad time in c: '25:61:00'");
    }

    @Test
    void rejectsEmptyFilesAndUnterminatedQuotes() {
        assertThatThrownBy(() -> read("", new ArrayList<>()))
                .isInstanceOf(GtfsFormatException.class)
                .hasMessage("test.txt is empty");
        assertThatThrownBy(() -> read("a\n\"open\n", new ArrayList<>()))
                .isInstanceOf(GtfsFormatException.class)
                .hasMessageContaining("unterminated quoted field");
    }
}
