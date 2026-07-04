package com.reactor.sample.cache.writer.config;

import com.reactor.rust.cache.projection.CacheWriterProjectionSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheProjectionSettingsTest {

    @AfterEach
    void clearRuntimeOverrides() {
        System.clearProperty("sample.writer.projections");
        System.clearProperty("sample.writer.campaign.cache-ttl-ms");
        System.clearProperty("sample.writer.campaign.interval-ms");
        System.clearProperty("sample.writer.cache-ttl-safety-margin-ms");
    }

    @Test
    void defaultProjectionSettingsDoNotNeedSafetyAdjustment() {
        CacheWriterProjectionSettings campaign = projection("campaign");

        assertEquals(60000L, campaign.intervalMillis());
        assertEquals(600000L, campaign.configuredCacheTtlMillis());
        assertEquals(600000L, campaign.effectiveCacheTtlMillis());
        assertTrue(campaign.warnings().isEmpty());
    }

    @Test
    void raisesEffectiveTtlWhenConfiguredTtlIsShorterThanInterval() {
        System.setProperty("sample.writer.campaign.cache-ttl-ms", "1000");

        CacheWriterProjectionSettings campaign = projection("campaign");

        assertEquals(60000L, campaign.intervalMillis());
        assertEquals(1000L, campaign.configuredCacheTtlMillis());
        assertEquals(90000L, campaign.effectiveCacheTtlMillis());
        assertFalse(campaign.warnings().isEmpty());
        assertTrue(campaign.warnings().get(0).contains("cache-ttl-ms-must-be-greater-than-interval"));
    }

    @Test
    void ignoresInvalidRuntimeOverrideAndUsesFileDefault() {
        System.setProperty("sample.writer.campaign.interval-ms", "wrong");

        CacheWriterProjectionSettings campaign = projection("campaign");

        assertEquals(60000L, campaign.intervalMillis());
        assertEquals(600000L, campaign.effectiveCacheTtlMillis());
        assertFalse(campaign.warnings().isEmpty());
        assertTrue(campaign.warnings().get(0).contains("must-be-long"));
    }

    @Test
    void resolvesProjectionListFromRuntimeOverride() {
        System.setProperty("sample.writer.projections", "detail,campaign,detail");

        List<CacheWriterProjectionSettings> settings =
                CacheWriterProjectionSettings.resolveAll(WriterProperties.load(), "sample.writer");

        assertEquals(2, settings.size());
        assertEquals("detail", settings.get(0).name());
        assertEquals("campaign", settings.get(1).name());
    }

    private static CacheWriterProjectionSettings projection(String name) {
        List<CacheWriterProjectionSettings> settings =
                CacheWriterProjectionSettings.resolveAll(WriterProperties.load(), "sample.writer");
        return settings.stream()
                .filter(setting -> setting.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
