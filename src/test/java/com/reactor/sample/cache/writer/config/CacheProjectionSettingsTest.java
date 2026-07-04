package com.reactor.sample.cache.writer.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheProjectionSettingsTest {

    @AfterEach
    void clearRuntimeOverrides() {
        System.clearProperty("sample.writer.campaign.cache-ttl-ms");
        System.clearProperty("sample.writer.campaign.interval-ms");
        System.clearProperty("sample.writer.cache-ttl-safety-margin-ms");
    }

    @Test
    void defaultProjectionSettingsDoNotNeedSafetyAdjustment() {
        CacheProjectionSettings campaign = projection("campaign");

        assertEquals(30000L, campaign.intervalMillis());
        assertEquals(120000L, campaign.configuredCacheTtlMillis());
        assertEquals(120000L, campaign.effectiveCacheTtlMillis());
        assertTrue(campaign.warnings().isEmpty());
    }

    @Test
    void raisesEffectiveTtlWhenConfiguredTtlIsShorterThanInterval() {
        System.setProperty("sample.writer.campaign.cache-ttl-ms", "1000");

        CacheProjectionSettings campaign = projection("campaign");

        assertEquals(30000L, campaign.intervalMillis());
        assertEquals(1000L, campaign.configuredCacheTtlMillis());
        assertEquals(60000L, campaign.effectiveCacheTtlMillis());
        assertFalse(campaign.warnings().isEmpty());
        assertTrue(campaign.warnings().get(0).contains("cache-ttl-ms-must-be-greater-than-interval"));
    }

    @Test
    void ignoresInvalidRuntimeOverrideAndUsesFileDefault() {
        System.setProperty("sample.writer.campaign.interval-ms", "wrong");

        CacheProjectionSettings campaign = projection("campaign");

        assertEquals(30000L, campaign.intervalMillis());
        assertEquals(120000L, campaign.effectiveCacheTtlMillis());
        assertFalse(campaign.warnings().isEmpty());
        assertTrue(campaign.warnings().get(0).contains("must-be-long"));
    }

    private static CacheProjectionSettings projection(String name) {
        List<CacheProjectionSettings> settings = CacheProjectionSettings.resolveAll(WriterProperties.load());
        return settings.stream()
                .filter(setting -> setting.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
