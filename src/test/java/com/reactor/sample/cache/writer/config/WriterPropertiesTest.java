package com.reactor.sample.cache.writer.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WriterPropertiesTest {

    @Test
    void loadsProjectionNamespacesAndTtlDefaults() {
        WriterProperties properties = WriterProperties.load();

        assertEquals("crm.customer", properties.get("sample.writer.namespace"));
        assertEquals(2, properties.getInt("sample.writer.scheduler-threads"));
        assertEquals("crm.customer.detail", properties.get("sample.writer.detail.namespace"));
        assertEquals("crm.customer.segment", properties.get("sample.writer.segment.namespace"));
        assertEquals("crm.customer.status", properties.get("sample.writer.status.namespace"));
        assertEquals("crm.customer.campaign", properties.get("sample.writer.campaign.namespace"));
        assertEquals("crm.customer.meta", properties.get("sample.writer.meta.namespace"));
        assertEquals("crm.customer.detail.refresh", properties.get("sample.writer.detail.lock-name"));
        assertEquals("crm.customer.segment.refresh", properties.get("sample.writer.segment.lock-name"));
        assertEquals("crm.customer.status.refresh", properties.get("sample.writer.status.lock-name"));
        assertEquals("crm.customer.campaign.refresh", properties.get("sample.writer.campaign.lock-name"));
        assertEquals("crm.customer.meta.refresh", properties.get("sample.writer.meta.lock-name"));

        assertEquals(600000L, properties.getLong("sample.writer.cache-ttl-ms"));
        assertEquals(300000L, properties.getLong("sample.writer.detail.interval-ms"));
        assertEquals(1800000L, properties.getLong("sample.writer.detail.cache-ttl-ms"));
        assertEquals(120000L, properties.getLong("sample.writer.segment.interval-ms"));
        assertEquals(300000L, properties.getLong("sample.writer.segment.cache-ttl-ms"));
        assertEquals(120000L, properties.getLong("sample.writer.status.interval-ms"));
        assertEquals(300000L, properties.getLong("sample.writer.status.cache-ttl-ms"));
        assertEquals(30000L, properties.getLong("sample.writer.campaign.interval-ms"));
        assertEquals(120000L, properties.getLong("sample.writer.campaign.cache-ttl-ms"));
        assertEquals(60000L, properties.getLong("sample.writer.meta.interval-ms"));
        assertEquals(300000L, properties.getLong("sample.writer.meta.cache-ttl-ms"));
    }
}
