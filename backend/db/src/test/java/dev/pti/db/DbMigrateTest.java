package dev.pti.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DbMigrateTest {

    @Test
    void exitsWithConfigurationErrorWhenAPasswordIsMissing() {
        Map<String, String> env = Map.of("PTI_OWNER_PASSWORD", "x", "TICKETING_OWNER_PASSWORD", "x");

        assertThat(DbMigrate.run(env)).isEqualTo(DbMigrate.EXIT_BAD_CONFIGURATION);
    }

    @Test
    void everySetReadsItsOwnLocationAndOwner() {
        assertThat(MigrationSet.WAREHOUSE.location()).isEqualTo("classpath:db/migration/warehouse");
        assertThat(MigrationSet.TICKETING.owner()).isEqualTo("ticketing_owner");
        assertThat(MigrationSet.SIM.urlVariable()).isEqualTo("PTI_DB_SIM_URL");
    }
}
