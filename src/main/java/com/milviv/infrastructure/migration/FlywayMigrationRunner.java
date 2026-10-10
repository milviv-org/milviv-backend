package com.milviv.infrastructure.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.milviv.infrastructure.container.Inject;
import com.milviv.infrastructure.persistence.DatabaseConnectionManager;

public class FlywayMigrationRunner {

    private static final Logger logger = LoggerFactory.getLogger(FlywayMigrationRunner.class);

    private final DatabaseConnectionManager dbManager;

    @Inject
    public FlywayMigrationRunner(DatabaseConnectionManager dbManager) {
        this.dbManager = dbManager;
    }

    public void migrate() {
        logger.info("Running Flywat migrations...");

        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(this.dbManager.getDataSource())
                    .locations("classpath:db/migration")
                    .validateOnMigrate(true)
                    .load();

            MigrateResult result = flyway.migrate();

            logger.info("Flyway migrations applied: {}, target version: {}", result.migrationsExecuted, result.targetSchemaVersion);
        } catch (FlywayException e) {
            logger.error("Database migration failed: {}", e.getMessage(), e);
            throw new MigrationException("Failed to apply database migrations", e);
        }
    }

}
