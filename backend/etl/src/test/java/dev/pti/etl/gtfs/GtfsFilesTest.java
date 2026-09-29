package dev.pti.etl.gtfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.pti.common.error.FatalException;
import dev.pti.etl.raw.RawZone;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

class GtfsFilesTest {

    @TempDir
    Path dir;

    private static final String AGENCY =
            "agency_id,agency_name,agency_url,agency_timezone\n0,MT,https://x,America/Chicago\n";

    private Path zip(Map<String, String> entries) throws IOException {
        Path zip = dir.resolve("feed-" + entries.hashCode() + ".zip");
        try (OutputStream out = Files.newOutputStream(zip);
                ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return zip;
    }

    private static Map<String, String> minimal() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("agency.txt", AGENCY);
        files.put("routes.txt", "route_id,route_type,route_url\n1,3,x\n");
        files.put("stops.txt", "stop_id\n1\n");
        files.put("trips.txt", "route_id,service_id,trip_id\n1,1,1\n");
        files.put("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n");
        files.put("shapes.txt", "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n");
        files.put("calendar_dates.txt", "service_id,date,exception_type\n");
        files.put("levels.txt", "level_id\n");
        files.put("sub/routes.txt", "x\n");
        return files;
    }

    @Test
    void aGoodFeedIsExtractedAndInspected() throws IOException {
        FeedExtractor extractor = new FeedExtractor(1_000_000, 20);
        Path target = dir.resolve("feed");

        FeedExtractor.Extracted extracted = extractor.extract(zip(minimal()), target);

        assertThat(extracted.files()).contains("agency.txt", "stop_times.txt").doesNotContain("levels.txt");
        assertThat(extracted.ignored()).containsExactlyInAnyOrder("levels.txt", "sub/routes.txt");
        FeedStructure.Inspection inspection = FeedStructure.inspect(target, extracted, 5);
        assertThat(inspection.zone()).hasToString("America/Chicago");
        assertThat(inspection.issues().hasErrors()).isFalse();
        assertThat(inspection.issues().count(FeedCheck.GV_14)).isEqualTo(2);
        assertThat(inspection.extraColumns()).containsEntry("routes.txt", List.of("route_url"));
    }

    @Test
    void structuralErrorsAreCollected() throws IOException {
        Map<String, String> files = minimal();
        files.remove("shapes.txt");
        files.remove("calendar_dates.txt");
        files.put("trips.txt", "route_id,service_id\n1,1\n");
        files.put("agency.txt", AGENCY + "1,Other,https://y,America/New_York\n");
        Path target = dir.resolve("feed");
        FeedExtractor.Extracted extracted = new FeedExtractor(1_000_000, 20).extract(zip(files), target);

        FeedStructure.Inspection inspection = FeedStructure.inspect(target, extracted, 5);

        assertThat(inspection.zone()).isNull();
        assertThat(inspection.issues().count(FeedCheck.GV_01)).isEqualTo(2);
        assertThat(inspection.issues().count(FeedCheck.GV_02)).isEqualTo(1);
        assertThat(inspection.issues().count(FeedCheck.GV_03)).isEqualTo(1);
    }

    @Test
    void badTimeZonesAndEmptyFilesAreErrors() throws IOException {
        Map<String, String> files = minimal();
        files.put("agency.txt", "agency_name,agency_url,agency_timezone\nMT,https://x,Mars/Olympus\n");
        files.put("stops.txt", "");
        Path target = dir.resolve("feed");
        FeedStructure.Inspection inspection =
                FeedStructure.inspect(target, new FeedExtractor(1_000_000, 20).extract(zip(files), target), 5);
        assertThat(inspection.issues().count(FeedCheck.GV_03)).isEqualTo(1);
        assertThat(inspection.issues().count(FeedCheck.GV_01)).isEqualTo(1);
    }

    @Test
    void dangerousZipsAreRefused() throws IOException {
        Path target = dir.resolve("feed");
        Map<String, String> slip = minimal();
        slip.put("../../evil.txt", "x");
        assertThatThrownBy(() -> new FeedExtractor(1_000_000, 20).extract(zip(slip), target))
                .isInstanceOf(FeedRejectedException.class)
                .hasMessageContaining("outside the feed directory");
        assertThat(dir.getParent().resolve("evil.txt")).doesNotExist();

        assertThatThrownBy(() -> new FeedExtractor(1_000_000, 3).extract(zip(minimal()), target))
                .hasMessageContaining("more than 3 entries");
        assertThatThrownBy(() -> new FeedExtractor(50, 20).extract(zip(minimal()), target))
                .hasMessageContaining("more than the allowed size");

        Map<String, String> bomb = minimal();
        bomb.put("shapes.txt", "0".repeat(3 * 1024 * 1024));
        assertThatThrownBy(() -> new FeedExtractor(100_000_000, 20).extract(zip(bomb), target))
                .satisfies(e -> assertThat(((FeedRejectedException) e).check()).isEqualTo(FeedCheck.GV_01))
                .hasMessageContaining("expands more than 100 times");

        Path notZip = Files.writeString(dir.resolve("x.zip"), "not a zip");
        assertThatThrownBy(() -> new FeedExtractor(100, 20).extract(notZip, target))
                .hasMessageContaining("Not a valid zip");
    }

    private RawZone raw() {
        RawZone raw = mock(RawZone.class);
        when(raw.bucket()).thenReturn("raw");
        when(raw.key(any())).thenAnswer(call -> call.getArgument(0));
        return raw;
    }

    @Test
    void sourcesAreCheckedAgainstDoc21() throws IOException {
        Path feeds = Files.createDirectories(dir.resolve("feeds"));
        FeedSource source = new FeedSource(List.of(feeds), false, raw());

        assertThat(source.check(feeds.resolve("a.zip").toUri().toString())).isNotNull();
        assertThatThrownBy(() -> source.check("file:/etc/hosts")).hasMessageContaining("outside the allowed");
        assertThatThrownBy(() -> source.check("https://example.org/feed.zip")).hasMessageContaining("disabled");
        assertThatThrownBy(() -> source.check("ftp://x/feed.zip")).hasMessageContaining("Unsupported");
        assertThatThrownBy(() -> source.check("not a uri")).hasMessageContaining("not a URI");
        assertThat(source.rawKeyOf(source.check("s3://raw/gtfs-static/abc.zip")))
                .contains("gtfs-static/abc.zip");
        assertThatThrownBy(() -> source.check("s3://other/gtfs-static/abc.zip")).hasMessageContaining("s3 sources");
        assertThatThrownBy(() -> source.check("s3://raw/gtfs.vehicle_positions/x"))
                .hasMessageContaining("s3 sources");
        assertThat(new FeedSource(List.of(feeds), true, raw()).check("https://example.org/f.zip"))
                .isNotNull();
    }

    @Test
    void aFileSourceIsCopiedAndHashed() throws IOException {
        Path feeds = Files.createDirectories(dir.resolve("feeds"));
        Path zip = Files.writeString(feeds.resolve("a.zip"), "abc");
        FeedSource source = new FeedSource(List.of(feeds), false, raw());

        String hash = source.copy(zip.toUri(), dir.resolve("work/feed.zip"));

        assertThat(hash).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(FeedSource.sha256(dir.resolve("work/feed.zip"))).isEqualTo(hash);
        assertThatThrownBy(() -> source.copy(feeds.resolve("missing.zip").toUri(), dir.resolve("w2/feed.zip")))
                .isInstanceOf(FatalException.class);
    }

    @Test
    void anS3SourceIsDownloadedFromTheRawZone() {
        RawZone raw = raw();
        org.mockito.Mockito.doAnswer(call -> {
                    Path target = call.getArgument(1);
                    Files.writeString(target, "abc");
                    return null;
                })
                .when(raw)
                .download(any(), any());
        FeedSource source = new FeedSource(List.of(dir), false, raw);

        String hash = source.copy(source.check("s3://raw/gtfs-static/x.zip"), dir.resolve("s3/feed.zip"));

        assertThat(hash).startsWith("ba7816bf");
        verify(raw).download(org.mockito.ArgumentMatchers.eq("gtfs-static/x.zip"), any());
    }

    @Test
    void theWorkspaceIsPerJobInstanceAndCleaned() throws IOException {
        FeedWorkspace workspace = new FeedWorkspace(dir);
        Files.createDirectories(workspace.files(1));
        Files.createDirectories(workspace.files(2));
        Files.setLastModifiedTime(workspace.dir(2), FileTime.from(Instant.now().minus(Duration.ofDays(3))));

        assertThat(workspace.file(1, GtfsTable.STOPS).endsWith(Path.of("1", "feed", "stops.txt")))
                .isTrue();
        workspace.deleteOlderThan(Duration.ofDays(2));
        assertThat(workspace.dir(2)).doesNotExist();
        assertThat(workspace.dir(1)).exists();
        workspace.delete(1);
        assertThat(workspace.dir(1)).doesNotExist();
        new FeedWorkspace(dir.resolve("absent")).deleteOlderThan(Duration.ZERO);
    }

    @Test
    void theWorkspaceListenerRestoresAMissingFeedFromTheRawZone() throws IOException {
        Path source = zip(minimal());
        String hash = FeedSource.sha256(source);
        RawZone raw = raw();
        org.mockito.Mockito.doAnswer(call -> {
                    Files.createDirectories(((Path) call.getArgument(1)).getParent());
                    Files.copy(source, (Path) call.getArgument(1), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return null;
                })
                .when(raw)
                .download(any(), any());
        FeedWorkspace workspace = new FeedWorkspace(dir.resolve("work"));
        FeedWorkspaceListener listener = new FeedWorkspaceListener(workspace, new FeedExtractor(1_000_000, 20), raw);
        StepExecution step = MetaDataInstanceFactory.createStepExecution();
        step.getJobExecution().getExecutionContext().putString(FeedContext.FEED_HASH, hash);
        long instance = step.getJobExecution().getJobInstance().getInstanceId();

        listener.beforeStep(step);
        assertThat(workspace.file(instance, GtfsTable.AGENCY)).exists();
        listener.beforeStep(step);
        verify(raw, org.mockito.Mockito.times(1)).download(any(), any());

        workspace.delete(instance);
        step.getJobExecution().getExecutionContext().putString(FeedContext.FEED_HASH, "0".repeat(64));
        assertThatThrownBy(() -> listener.beforeStep(step)).isInstanceOf(FatalException.class);
    }

    @Test
    void skipCollectorKeepsRowErrorsInTheStepThenTheJob() {
        StepExecution step = MetaDataInstanceFactory.createStepExecution();
        GtfsSkipCollector collector = new GtfsSkipCollector(5, () -> step);

        collector.onSkipInRead(new FlatFileParseException("Parsing error", "x", 4));
        collector.onSkipInRead(new IllegalStateException("odd"));
        collector.onSkipInProcess(new GtfsRow("stops.txt", 5, Map.of()), new GtfsRowException("stop_id is required"));
        collector.onSkipInWrite(
                new GtfsInsert("stops.txt", 6, new MapSqlParameterSource()),
                new org.springframework.dao.DuplicateKeyException(
                        "PreparedStatementCallback",
                        new java.sql.SQLException("ERROR: duplicate key value\n  Detail: x")));
        collector.afterStep(step);

        GtfsIssues issues = GtfsIssues.fromJson(
                step.getJobExecution()
                        .getExecutionContext()
                        .getString(FeedContext.ROW_ISSUES_PREFIX + step.getStepName()),
                5);
        assertThat(issues.count(FeedCheck.GV_04)).isEqualTo(4);
        assertThat(issues.entries(true).toString()).contains("duplicate key value", "\"line\":6");
        StepExecution clean = MetaDataInstanceFactory.createStepExecution();
        new GtfsSkipCollector(5, () -> clean).afterStep(clean);
        assertThat(clean.getJobExecution().getExecutionContext().isEmpty()).isTrue();
    }

    @Test
    void theJobListenerLetsOnlyTheOldestLoadRunAndCounts() {
        JobRepository repository = mock(JobRepository.class);
        JobExecution older = MetaDataInstanceFactory.createJobExecution("GtfsStaticLoadJob", 1L, 4L);
        JobExecution newer = MetaDataInstanceFactory.createJobExecution("GtfsStaticLoadJob", 2L, 5L);
        when(repository.findRunningJobExecutions("GtfsStaticLoadJob")).thenReturn(java.util.Set.of(older, newer));
        FeedWorkspace workspace = new FeedWorkspace(dir);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        GtfsJobListener listener = new GtfsJobListener(repository, workspace, meters);

        listener.beforeJob(older);
        assertThatThrownBy(() -> listener.beforeJob(newer)).hasMessageContaining(GtfsJobListener.ANOTHER_RUNNING);

        older.setStatus(BatchStatus.COMPLETED);
        older.setExitStatus(new ExitStatus("NOOP"));
        listener.afterJob(older);
        assertThat(meters.get("pti.gtfs.load").tag("outcome", "noop").counter().count())
                .isEqualTo(1.0);
        older.setExitStatus(new ExitStatus("REJECTED"));
        assertThat(GtfsJobListener.outcome(older)).isEqualTo("rejected");
        older.setExitStatus(ExitStatus.COMPLETED);
        assertThat(GtfsJobListener.outcome(older)).isEqualTo("activated");
        older.getExecutionContext().putString(FetchFeedTasklet.REACTIVATING, "true");
        assertThat(GtfsJobListener.outcome(older)).isEqualTo("reactivated");
        older.setStatus(BatchStatus.FAILED);
        assertThat(GtfsJobListener.outcome(older)).isEqualTo("failed");
    }
}
