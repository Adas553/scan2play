package com.scan2play.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review item 5.5: production has no default database user or password — without PGUSER / PGPASSWORD the application does not
 * start, instead of trying a well-known password. The local defaults live in the profile {@code local} only.
 */
class DatabaseCredentialsTest {

    private static Properties load(String name) throws IOException {
        return PropertiesLoaderUtils.loadProperties(new ClassPathResource(name));
    }

    @Test
    void theMainConfiguration_hasNoDefaultUserOrPassword() throws IOException {
        Properties main = load("application.properties");

        assertThat(main.getProperty("spring.datasource.username")).isEqualTo("${PGUSER}");
        assertThat(main.getProperty("spring.datasource.password")).isEqualTo("${PGPASSWORD}");
    }

    @Test
    void theLocalProfile_keepsTheDevelopersDefaults() throws IOException {
        Properties local = load("application-local.properties");

        assertThat(local.getProperty("spring.datasource.username")).isEqualTo("${PGUSER:postgres}");
        assertThat(local.getProperty("spring.datasource.password")).startsWith("${PGPASSWORD:");
    }
}
