package dev.pti.etl.gtfs;

import dev.pti.testing.GtfsFixtures;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The mini feed (DOC-44 §5.1) with test changes. Every build gets its own {@code feed_version}, so its hash is new
 * and the job really loads it; broken variants of G-04 are made with {@link #edit}.
 */
public final class FeedBuilder {

    private final Map<String, byte[]> files = new LinkedHashMap<>();

    private FeedBuilder() {
        for (String name : GtfsFixtures.MINI_FEED_FILES) {
            try (InputStream in = GtfsFixtures.open(name)) {
                files.put(name, in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        edit("feed_info.txt", text -> text.replace(",1790175878,", "," + UUID.randomUUID() + ","));
    }

    public static FeedBuilder mini() {
        return new FeedBuilder();
    }

    public FeedBuilder edit(String file, UnaryOperator<String> change) {
        String text = new String(files.get(file), StandardCharsets.UTF_8);
        files.put(file, change.apply(text).getBytes(StandardCharsets.UTF_8));
        return this;
    }

    public FeedBuilder remove(String file) {
        files.remove(file);
        return this;
    }

    public FeedBuilder add(String entry, byte[] content) {
        files.put(entry, content);
        return this;
    }

    public String text(String file) {
        return new String(files.get(file), StandardCharsets.UTF_8);
    }

    /** Data rows of a file (header and blank lines excluded). */
    public long rows(String file) {
        return text(file).lines().skip(1).filter(l -> !l.isBlank()).count();
    }

    public Path write(Path dir) {
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve("feed-" + UUID.randomUUID() + ".zip");
            try (OutputStream out = Files.newOutputStream(target);
                    ZipOutputStream zip = new ZipOutputStream(out)) {
                for (Map.Entry<String, byte[]> file : files.entrySet()) {
                    zip.putNextEntry(new ZipEntry(file.getKey()));
                    zip.write(file.getValue());
                    zip.closeEntry();
                }
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
