package dev.pti.simulator.scenario.domain;

import dev.pti.simulator.motion.domain.Seeds;
import java.util.UUID;

/**
 * Picks messages by hash (DOC-25 §7.4): {@code hash(seed, message_id) mod 10⁶ < ratio × 10⁶}, so the share matches
 * the ratio and the same message is always picked the same way.
 */
public final class Selection {

    private static final long SCALE = 1_000_000;

    private Selection() {}

    public static boolean picked(long seed, String salt, UUID messageId, double ratio) {
        return Math.floorMod(value(seed, salt, messageId), SCALE) < Math.round(ratio * SCALE);
    }

    /** A second, independent value for the same message, e.g. which kind of corruption. */
    public static long value(long seed, String salt, UUID messageId) {
        return Seeds.of(seed, salt, messageId.getMostSignificantBits(), messageId.getLeastSignificantBits());
    }
}
