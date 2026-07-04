package com.reactor.sample.cache.writer.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WriterPropertiesTest {

    @Test
    void loadsProjectionNamespacesAndTtlDefaults() {
        WriterProperties properties = WriterProperties.load();

        assertEquals("crm.customer", properties.get("sample.writer.namespace"));
        assertEquals("crm.customer.detail", properties.get("sample.writer.detail.namespace"));
        assertEquals("crm.customer.segment", properties.get("sample.writer.segment.namespace"));
        assertEquals("crm.customer.status", properties.get("sample.writer.status.namespace"));
        assertEquals("crm.customer.campaign", properties.get("sample.writer.campaign.namespace"));
        assertEquals("crm.customer.meta", properties.get("sample.writer.meta.namespace"));

        assertEquals(600000L, properties.getLong("sample.writer.cache-ttl-ms"));
        assertEquals(1800000L, properties.getLong("sample.writer.detail.cache-ttl-ms"));
        assertEquals(300000L, properties.getLong("sample.writer.segment.cache-ttl-ms"));
        assertEquals(300000L, properties.getLong("sample.writer.status.cache-ttl-ms"));
        assertEquals(120000L, properties.getLong("sample.writer.campaign.cache-ttl-ms"));
        assertEquals(300000L, properties.getLong("sample.writer.meta.cache-ttl-ms"));
    }
}
