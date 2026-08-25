package com.lipi.serialreceiver.model;

/**
 * Combines the last RFID tag read with the last scale weight reading and
 * computes a match/mismatch verdict against the item's expected weight.
 */
public class ScanResult {

    public enum Verdict {
        AWAITING_TAG,       // No tag scanned yet
        UNKNOWN_TAG,        // Tag scanned but not found in inventory
        AWAITING_WEIGHT,    // Known tag scanned, waiting for a stable scale reading
        MATCH,              // Weight within tolerance of expected
        MISMATCH            // Weight outside tolerance of expected
    }

    private final String epc;
    private final InventoryItem item; // null if unknown tag
    private final Double weightKg;    // null if not yet weighed
    private final Verdict verdict;
    private final double toleranceKg;

    public ScanResult(String epc, InventoryItem item, Double weightKg, double toleranceKg) {
        this.epc = epc;
        this.item = item;
        this.weightKg = weightKg;
        this.toleranceKg = toleranceKg;
        this.verdict = computeVerdict();
    }

    private Verdict computeVerdict() {
        if (epc == null) return Verdict.AWAITING_TAG;
        if (item == null) return Verdict.UNKNOWN_TAG;
        if (weightKg == null) return Verdict.AWAITING_WEIGHT;
        double diff = Math.abs(weightKg - item.getExpectedWeightKg());
        return diff <= toleranceKg ? Verdict.MATCH : Verdict.MISMATCH;
    }

    public String getEpc() {
        return epc;
    }

    public InventoryItem getItem() {
        return item;
    }

    public Double getWeightKg() {
        return weightKg;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public double getToleranceKg() {
        return toleranceKg;
    }
}
