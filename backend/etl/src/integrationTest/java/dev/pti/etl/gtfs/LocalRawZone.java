package dev.pti.etl.gtfs;

import dev.pti.etl.raw.RawZone;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/** The raw zone as a local directory, for tests that do not need SeaweedFS. */
public final class LocalRawZone implements RawZone {

    private final Path root;

    public LocalRawZone(Path root) {
        this.root = root;
    }

    @Override
    public String bucket() {
        return "raw";
    }

    @Override
    public String key(String relative) {
        return relative;
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(root.resolve(key));
    }

    @Override
    public void put(String key, Path file) {
        try {
            Path target = root.resolve(key);
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        Path start = root.resolve(prefix.contains("/") ? prefix.substring(0, prefix.lastIndexOf('/')) : "");
        if (!Files.isDirectory(start)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(start)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(k -> k.startsWith(prefix))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public InputStream open(String key) {
        Path source = root.resolve(key);
        if (!Files.exists(source)) {
            throw new RawObjectMissingException(key);
        }
        try {
            return Files.newInputStream(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes an object, for tests that prepare raw zone content. */
    public void write(String key, byte[] content) {
        try {
            Path target = root.resolve(key);
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void download(String key, Path target) {
        Path source = root.resolve(key);
        if (!Files.exists(source)) {
            throw new RawObjectMissingException(key);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
