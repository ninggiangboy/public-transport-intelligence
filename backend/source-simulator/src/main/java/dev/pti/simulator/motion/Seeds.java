package dev.pti.simulator.motion;

import java.time.LocalDate;

/**
 * Deterministic 64-bit seeds from mixed keys (DOC-25 §5): the same seed and the same key always give the same
 * value, on every JVM. Strings are hashed with FNV-1a, and every part goes through the SplitMix64 finalizer.
 */
public final class Seeds {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private Seeds() {}

    public static long of(long seed, Object... parts) {
        long h = mix(seed);
        for (Object part : parts) {
            h = mix(h ^ hash(part));
        }
        return h;
    }

    /** A value in [0, 1) from a seed. */
    public static double unit(long seed) {
        return (mix(seed) >>> 11) * 0x1.0p-53;
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    private static long hash(Object part) {
        return switch (part) {
            case String s -> fnv(s);
            case Long l -> l;
            case Integer i -> i;
            case LocalDate d -> d.toEpochDay();
            case Enum<?> e -> fnv(e.name());
            default -> throw new IllegalArgumentException("Cannot seed from " + part.getClass());
        };
    }

    private static long fnv(String s) {
        long h = FNV_OFFSET;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= FNV_PRIME;
        }
        return h;
    }
}
