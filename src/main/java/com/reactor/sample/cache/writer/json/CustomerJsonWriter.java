package com.reactor.sample.cache.writer.json;

import com.reactor.sample.model.customer.CustomerCounts;
import com.reactor.sample.model.customer.SampleCustomer;
import com.reactor.sample.utility.json.SampleJsonWriter;

import java.time.Instant;
import java.util.List;

public final class CustomerJsonWriter extends SampleJsonWriter {

    public byte[] customer(SampleCustomer customer) {
        return customerDetail(customer);
    }

    public byte[] customerDetail(SampleCustomer customer) {
        StringBuilder json = json(768);
        appendCustomerDetail(json, customer);
        return utf8(json);
    }

    public byte[] customers(List<SampleCustomer> customers) {
        return customerSummaries("all", "all", customers);
    }

    public byte[] customerSummaries(String indexName, String indexValue, List<SampleCustomer> customers) {
        StringBuilder json = json(Math.max(128, customers.size() * 256));
        json.append('{');
        json.append("\"index\":{");
        stringField(json, "name", indexName).append(',');
        stringField(json, "value", indexValue).append(',');
        field(json, "size", customers.size());
        json.append("},\"customers\":");
        appendCustomerSummaryArray(json, customers);
        json.append('}');
        return utf8(json);
    }

    public byte[] campaignCandidates(String campaignCode, List<SampleCustomer> customers) {
        StringBuilder json = json(Math.max(160, customers.size() * 280));
        json.append('{');
        stringField(json, "campaignCode", campaignCode).append(',');
        json.append("\"selection\":{");
        stringField(json, "source", "postgresql-snapshot").append(',');
        stringField(json, "rule", "active customers with fresh precomputed profile").append(',');
        field(json, "candidateCount", customers.size());
        json.append("},\"candidates\":");
        appendCustomerSummaryArray(json, customers);
        json.append('}');
        return utf8(json);
    }

    public byte[] meta(CustomerCounts counts, int versionRows, Instant generatedAt,
            List<String> segments, List<String> statuses) {
        StringBuilder json = json(320);
        json.append('{');
        field(json, "total", counts.total()).append(',');
        field(json, "active", counts.active()).append(',');
        field(json, "passive", counts.passive()).append(',');
        field(json, "versionRows", versionRows).append(',');
        stringField(json, "generatedAt", generatedAt.toString()).append(',');
        stringArrayField(json, "segments", segments).append(',');
        stringArrayField(json, "statuses", statuses).append(',');
        json.append("\"availableLookups\":[");
        quote(json, "by-id").append(',');
        quote(json, "by-customer-no").append(',');
        quote(json, "by-segment").append(',');
        quote(json, "by-status").append(',');
        quote(json, "campaign-candidates");
        json.append("]}");
        return utf8(json);
    }

    public byte[] meta(CustomerCounts counts, int versionRows, Instant generatedAt) {
        return meta(counts, versionRows, generatedAt, List.of(), List.of());
    }

    private void appendCustomerDetail(StringBuilder json, SampleCustomer customer) {
        json.append('{');
        json.append("\"customer\":{");
        field(json, "id", customer.id()).append(',');
        stringField(json, "customerNo", customer.customerNo()).append(',');
        stringField(json, "fullName", customer.fullName()).append(',');
        stringField(json, "status", customer.status());
        json.append("},\"contact\":{");
        stringField(json, "email", customer.email()).append(',');
        json.append("\"phones\":[");
        quote(json, "+90-532-" + padded(customer.id(), 4)).append(',');
        quote(json, "+90-212-" + padded(customer.id() + 77, 4));
        json.append("]},\"profile\":{");
        stringField(json, "segment", customer.segment()).append(',');
        json.append("\"loyalty\":{");
        stringField(json, "tier", loyaltyTier(customer)).append(',');
        field(json, "points", loyaltyPoints(customer));
        json.append("},\"risk\":{");
        stringField(json, "level", riskLevel(customer)).append(',');
        field(json, "score", riskScore(customer));
        json.append("}},\"addresses\":[");
        appendAddress(json, "billing", customer).append(',');
        appendAddress(json, "shipping", customer);
        json.append("],\"lastOrders\":[");
        appendOrder(json, customer, 0).append(',');
        appendOrder(json, customer, 1);
        json.append("],\"audit\":{");
        stringField(json, "createdAt", customer.createdAt().toString()).append(',');
        stringField(json, "updatedAt", customer.updatedAt().toString()).append(',');
        stringField(json, "projection", "customer-detail-v1");
        json.append("}}");
    }

    private void appendCustomerSummaryArray(StringBuilder json, List<SampleCustomer> customers) {
        json.append('[');
        for (int i = 0; i < customers.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendCustomerSummary(json, customers.get(i));
        }
        json.append(']');
    }

    private void appendCustomerSummary(StringBuilder json, SampleCustomer customer) {
        json.append('{');
        field(json, "id", customer.id()).append(',');
        stringField(json, "customerNo", customer.customerNo()).append(',');
        stringField(json, "displayName", customer.fullName()).append(',');
        json.append("\"profile\":{");
        stringField(json, "segment", customer.segment()).append(',');
        stringField(json, "status", customer.status()).append(',');
        stringField(json, "tier", loyaltyTier(customer));
        json.append("},\"lastOrder\":{");
        stringField(json, "orderNo", orderNo(customer, 0)).append(',');
        field(json, "totalAmount", orderTotal(customer, 0));
        json.append('}');
        json.append('}');
    }

    private StringBuilder appendAddress(StringBuilder json, String type, SampleCustomer customer) {
        json.append('{');
        stringField(json, "type", type).append(',');
        stringField(json, "city", city(customer)).append(',');
        stringField(json, "district", type.equals("billing") ? "Merkez" : "Operasyon").append(',');
        stringField(json, "country", "TR");
        json.append('}');
        return json;
    }

    private StringBuilder appendOrder(StringBuilder json, SampleCustomer customer, int index) {
        json.append('{');
        stringField(json, "orderNo", orderNo(customer, index)).append(',');
        stringField(json, "status", index == 0 ? "DELIVERED" : "PREPARING").append(',');
        field(json, "totalAmount", orderTotal(customer, index)).append(',');
        json.append("\"items\":[");
        json.append('{');
        stringField(json, "sku", "SKU-" + padded(customer.id() + index, 5)).append(',');
        stringField(json, "name", index == 0 ? "Aylık abonelik" : "Ek hizmet paketi").append(',');
        field(json, "quantity", index + 1);
        json.append('}');
        json.append("]}");
        return json;
    }

    private static String loyaltyTier(SampleCustomer customer) {
        if ("enterprise".equalsIgnoreCase(customer.segment())) {
            return "platinum";
        }
        if ("pilot".equalsIgnoreCase(customer.segment())) {
            return "gold";
        }
        return "standard";
    }

    private static long loyaltyPoints(SampleCustomer customer) {
        return 1_000 + customer.id() * 137;
    }

    private static String riskLevel(SampleCustomer customer) {
        return "passive".equalsIgnoreCase(customer.status()) ? "watch" : "normal";
    }

    private static long riskScore(SampleCustomer customer) {
        return "passive".equalsIgnoreCase(customer.status()) ? 68 : 18 + (customer.id() % 9);
    }

    private static String city(SampleCustomer customer) {
        return switch ((int) (customer.id() % 4)) {
            case 0 -> "İstanbul";
            case 1 -> "Ankara";
            case 2 -> "İzmir";
            default -> "Bursa";
        };
    }

    private static String orderNo(SampleCustomer customer, int index) {
        return "ORD-" + customer.id() + "-" + (202600 + index);
    }

    private static long orderTotal(SampleCustomer customer, int index) {
        return 500 + customer.id() * 25 + index * 75;
    }

    private static String padded(long value, int width) {
        String raw = Long.toString(Math.abs(value));
        if (raw.length() >= width) {
            return raw;
        }
        return "0".repeat(width - raw.length()) + raw;
    }
}
