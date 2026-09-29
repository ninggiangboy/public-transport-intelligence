package dev.pti.etl.gtfs;

import dev.pti.etl.raw.RawZone;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
