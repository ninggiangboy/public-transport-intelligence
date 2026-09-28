package dev.pti.db;

/**
 * The three Flyway migration sets, one per database, each run by that database's owner (DOC-17 §5).
 */
public enum MigrationSet {
    WAREHOUSE("warehouse", "pti_owner", "PTI_OWNER_PASSWORD", "jdbc:postgresql://pg-warehouse:5432/pti_warehouse"),
    TICKETING(
            "ticketing",
            "ticketing_owner",
            "TICKETING_OWNER_PASSWORD",
            "jdbc:postgresql://pg-source:5432/ticketing_source"),
    SIM("sim", "sim_owner", "SIM_OWNER_PASSWORD", "jdbc:postgresql://pg-source:5432/pti_sim");

    private final String id;
    private final String owner;
    private final String passwordVariable;
    private final String defaultUrl;

    MigrationSet(String id, String owner, String passwordVariable, String defaultUrl) {
        this.id = id;
        this.owner = owner;
        this.passwordVariable = passwordVariable;
        this.defaultUrl = defaultUrl;
    }

    public String id() {
        return id;
    }

    public String owner() {
        return owner;
    }

    public String location() {
        return "classpath:db/migration/" + id;
    }

    /** Environment variable holding the owner's password. */
    public String passwordVariable() {
        return passwordVariable;
    }

    /** Environment variable that overrides the JDBC URL; the default targets the compose hosts. */
    public String urlVariable() {
        return "PTI_DB_" + name() + "_URL";
    }

    public String defaultUrl() {
        return defaultUrl;
    }
}
