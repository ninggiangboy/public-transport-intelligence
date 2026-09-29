package dev.pti.etl.gtfs;

/** The feed checks of DOC-21 §4: an error rejects the feed, a warning is only reported. */
public enum FeedCheck {
    GV_01(true),
    GV_02(true),
    GV_03(true),
    GV_04(true),
    GV_05(true),
    GV_06(true),
    GV_07(true),
    GV_08(true),
    GV_09(true),
    GV_10(true),
    GV_11(false),
    GV_12(false),
    GV_13(false),
    GV_14(false),
    GV_15(false);

    private final boolean error;

    FeedCheck(boolean error) {
        this.error = error;
    }

    public boolean error() {
        return error;
    }

    /** {@code GV-01}. */
    public String code() {
        return name().replace('_', '-');
    }

    /** The SQL checks run by {@code validate}, in {@code sql/gtfs-validate/<code>.sql}. */
    public static FeedCheck[] sqlChecks() {
        return new FeedCheck[] {GV_05, GV_06, GV_07, GV_08, GV_09, GV_10, GV_11, GV_12, GV_13, GV_15};
    }
}
