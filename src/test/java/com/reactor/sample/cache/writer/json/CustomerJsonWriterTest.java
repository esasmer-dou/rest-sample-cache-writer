package com.reactor.sample.cache.writer.json;

import com.reactor.sample.model.customer.SampleCustomer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerJsonWriterTest {

    @Test
    void customerJsonPreservesUtf8AndEscapesControlCharacters() {
        SampleCustomer customer = new SampleCustomer(
                42,
                "CUST-42",
                "Mustafa Korkmaz \"Pilot\"",
                "pilot",
                "mustafa@example.com",
                "active",
                Instant.parse("2026-06-28T00:00:00Z"),
                Instant.parse("2026-06-28T00:00:00Z"));

        String json = new String(new CustomerJsonWriter().customer(customer), StandardCharsets.UTF_8);

        assertTrue(json.contains("\"customer\""));
        assertTrue(json.contains("\"fullName\":\"Mustafa Korkmaz \\\"Pilot\\\"\""));
        assertTrue(json.contains("\"profile\""));
        assertTrue(json.contains("\"lastOrders\""));
    }
}
