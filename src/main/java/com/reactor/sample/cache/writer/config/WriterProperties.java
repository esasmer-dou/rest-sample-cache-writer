package com.reactor.sample.cache.writer.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

public final class WriterProperties {

    private static final String RESOURCE = "rest-sample-cache-writer.properties";

    private final Properties properties;

    private WriterProperties(Properties properties) {
        this.properties = properties;
    }

    public static WriterProperties load() {
        Properties loaded = new Properties();
        try (InputStream input = WriterProperties.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing classpath resource: " + RESOURCE);
            }
            loaded.load(input);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + RESOURCE, e);
        }
        return new WriterProperties(loaded);
    }

    public Properties asProperties() {
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy;
    }

    public String get(String key) {
        String value = System.getProperty(key);
        if (value == null) {
            value = System.getenv(toEnvKey(key));
        }
        if (value == null) {
            value = properties.getProperty(key);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required property: " + key);
        }
        return value.trim();
    }

    public String getOptional(String key) {
        String value = System.getProperty(key);
        if (value == null) {
            value = System.getenv(toEnvKey(key));
        }
        if (value == null) {
            value = properties.getProperty(key);
        }
        return value == null ? "" : value.trim();
    }

    public int getInt(String key) {
        String value = get(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property must be an integer: " + key + "=" + value, e);
        }
    }

    public long getLong(String key) {
        String value = get(key);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property must be a long: " + key + "=" + value, e);
        }
    }

    public boolean getBoolean(String key) {
        String value = get(key);
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("Property must be a boolean: " + key + "=" + value);
    }

    private static String toEnvKey(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
