package com.reactor.sample.cache.writer.db;

import java.time.Instant;

public record SampleCustomer(
        long id,
        String customerNo,
        String fullName,
        String segment,
        String email,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
