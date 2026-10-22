package com.wolfsnetz.webserver.mods;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Catalogue-owned migrations: never modifies or baselines the existing users table. */
@Component("modCatalogueMigrations")
public class ModCatalogueMigrations implements InitializingBean {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate transactions;

    public ModCatalogueMigrations(JdbcTemplate jdbc, DataSource dataSource, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.transactions = transactions;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        var resource = new ClassPathResource("db/mods/V001__catalogue.sql");
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(resource.getContentAsString(StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8)));
        transactions.executeWithoutResult(status -> {
            jdbc.execute("CREATE TABLE IF NOT EXISTS mod_schema_versions (version INTEGER PRIMARY KEY, checksum TEXT NOT NULL)");
            var previous = jdbc.queryForList("SELECT checksum FROM mod_schema_versions WHERE version = 1", String.class);
            if (!previous.isEmpty()) {
                if (!checksum.equals(previous.get(0))) {
                    throw new IllegalStateException("Catalogue migration 1 checksum changed; add a new migration instead");
                }
                return;
            }
            new ResourceDatabasePopulator(resource).execute(dataSource);
            jdbc.update("INSERT INTO mod_schema_versions VALUES (1, ?)", checksum);
        });
    }
}
