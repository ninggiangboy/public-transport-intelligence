package dev.pti.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOC-28 §9 O-01 and O-02: the metrics an app must expose and the labels they may carry, from a catalog file
 * derived from DOC-28 §3, checked against a Prometheus scrape.
 *
 * <p>One line per metric: {@code <prometheus name> <counter|gauge|histogram|timer> <label,label|-> [lazy]}. A
 * {@code lazy} metric appears with its first event rather than at startup (a counter whose labels depend on the data).
 * Every sample of a {@code pti_} metric must belong to the catalog and carry only its labels, plus
 * {@code application} and, on histogram buckets, {@code le}.
 */
public final class MetricCatalog {

    private static final Pattern SAMPLE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{(.*)})?\\s");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final List<String> SUFFIXES = List.of("", "_bucket", "_count", "_sum", "_max", "_created");

    /** One metric of the catalog. */
    public record Entry(String name, String type, Set<String> labels, boolean lazy) {}

    private final List<Entry> entries;

    private MetricCatalog(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static MetricCatalog load(String resource) {
        try (InputStream in = MetricCatalog.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("No metric catalog at " + resource);
            }
            List<Entry> entries = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String text = line.strip();
                if (text.isEmpty() || text.startsWith("#")) {
                    continue;
                }
                String[] parts = text.split("\\s+");
                Set<String> labels = parts[2].equals("-") ? Set.of() : Set.of(parts[2].split(","));
                entries.add(new Entry(parts[0], parts[1], labels, parts.length > 3 && parts[3].equals("lazy")));
            }
            return new MetricCatalog(entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Catalog metrics that are not in the scrape; lazy ones only when {@code includeLazy}. */
    public List<String> missing(String scrape, boolean includeLazy) {
        Map<String, List<Set<String>>> samples = samples(scrape);
        List<String> missing = new ArrayList<>();
        for (Entry e : entries) {
            if (e.lazy() && !includeLazy) {
                continue;
            }
            String expected = e.type().equals("histogram") ? e.name() + "_bucket" : e.name();
            boolean found = e.type().equals("histogram")
                    ? samples.containsKey(expected)
                    : SUFFIXES.stream().anyMatch(s -> samples.containsKey(e.name() + s));
            if (!found) {
                missing.add(expected);
            }
        }
        return missing;
    }

    /** {@code pti_} samples outside the catalog, or with labels the catalog does not allow. */
    public List<String> violations(String scrape) {
        Set<String> problems = new TreeSet<>();
        samples(scrape).forEach((sample, labelSets) -> {
            if (!sample.startsWith("pti_")) {
                return;
            }
            Entry entry = entryOf(sample);
            if (entry == null) {
                problems.add(sample + ": not in the catalog");
                return;
            }
            Set<String> allowed = new HashSet<>(entry.labels());
            allowed.add("application");
            if (sample.endsWith("_bucket")) {
                allowed.add("le");
            }
            for (Set<String> labels : labelSets) {
                Set<String> extra = new TreeSet<>(labels);
                extra.removeAll(allowed);
                if (!extra.isEmpty()) {
                    problems.add(sample + ": unexpected labels " + extra);
                }
            }
        });
        return List.copyOf(problems);
    }

    private Entry entryOf(String sample) {
        for (Entry e : entries) {
            for (String suffix : SUFFIXES) {
                if (sample.equals(e.name() + suffix)) {
                    return e;
                }
            }
        }
        return null;
    }

    /** Sample name → the label-name sets seen on it. */
    static Map<String, List<Set<String>>> samples(String scrape) {
        Map<String, List<Set<String>>> samples = new LinkedHashMap<>();
        for (String line : scrape.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher m = SAMPLE.matcher(line);
            if (!m.find()) {
                continue;
            }
            Set<String> labels = new HashSet<>();
            if (m.group(3) != null) {
                Matcher l = LABEL.matcher(m.group(3));
                while (l.find()) {
                    labels.add(l.group(1));
                }
            }
            samples.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(labels);
        }
        return samples;
    }

    @Override
    public String toString() {
        return "MetricCatalog"
                + Arrays.toString(entries.stream().map(Entry::name).toArray());
    }
}
