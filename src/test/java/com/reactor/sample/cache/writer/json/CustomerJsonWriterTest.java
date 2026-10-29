package com.reactor.sample.cache.writer.json;

import com.reactor.sample.model.customer.SampleCustomer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class CustomerJsonWriterTest {

    @Test
    void refreshScopedEncoderMatchesFreshBuffersAndKeepsOutputsIndependent() {
        var writer = new CustomerJsonWriter();
        var encode = writer.detailEncoder();
        Instant time = Instant.parse("2026-06-28T00:00:00Z");
        byte[] saved = null;
        SampleCustomer first = null;
        for (String text : new String[]{"İıÇçĞğÖöÜü", "\"\\\n\t", "🚀", "\ud800x\udc00", "x".repeat(3000), "short"}) {
            var customer = new SampleCustomer(42, text, text, text, text, text, time, time);
            byte[] actual = encode.apply(customer);
            assertArrayEquals(writer.customerDetail(customer), actual);
            if (saved == null) { saved = actual; first = customer; }
        }
        assertArrayEquals(writer.customerDetail(first), saved);
    }

    @Test
    void representativeDetailExceedsOldCapacityAndFitsTheInitialBuffer() {
        Instant created = Instant.parse("2026-06-28T00:00:00.123456Z");
        Instant updated = Instant.parse("2026-06-29T12:34:56.123456Z");
        SampleCustomer customer = new SampleCustomer(10003, "PERF-010000", "Customer 10000",
                "standard", "customer10000@example.com", "active", created, updated);
        byte[] bytes = new CustomerJsonWriter().customerDetail(customer);
        assertTrue(bytes.length > 768 && bytes.length < 1024);
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"createdAt\":\"" + created + "\""));
        assertTrue(json.contains("\"updatedAt\":\"" + updated + "\""));
    }

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
