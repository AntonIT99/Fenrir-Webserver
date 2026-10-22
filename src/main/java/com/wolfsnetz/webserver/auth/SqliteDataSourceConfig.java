package com.wolfsnetz.webserver.auth;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.Properties;

@Configuration
public class SqliteDataSourceConfig
{
    @Bean
    DataSource dataSource(
        @Value("${spring.datasource.url}") String url,
        @Value("${spring.datasource.driver-class-name}") String driverClassName
    ) throws Exception {
        createParentDirectory(url);

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(driverClassName);
        dataSource.setUrl(url);
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("foreign_keys", "true");
        connectionProperties.setProperty("busy_timeout", "5000");
        dataSource.setConnectionProperties(connectionProperties);
        return dataSource;
    }

    private void createParentDirectory(String url) throws Exception
    {
        String prefix = "jdbc:sqlite:";
        if (!url.startsWith(prefix)) {
            return;
        }

        String databasePath = url.substring(prefix.length());
        if (databasePath.isBlank() || databasePath.equals(":memory:")) {
            return;
        }

        Path parent = Path.of(databasePath).toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
