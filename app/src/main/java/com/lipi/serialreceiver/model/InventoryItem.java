package com.lipi.serialreceiver.model;

/**
 * A single inventory record: one RFID tag (EPC) mapped to one physical item.
 * For now this catalog is hardcoded (see InventoryRepository); later it can be
 * swapped for a database or backend lookup without touching the rest of the app.
 */
public class InventoryItem {

    private final String epc;          // RFID tag ID, e.g. "E28011B0A502006E7CD189E3"
    private final String itemName;     // Human-readable product name
    private final String sku;          // Internal item code
    private final double expectedWeightKg; // Registered/expected weight for this item
    private final int stockQty;        // Quantity currently in inventory

    public InventoryItem(String epc, String itemName, String sku, double expectedWeightKg, int stockQty) {
        this.epc = epc;
        this.itemName = itemName;
        this.sku = sku;
        this.expectedWeightKg = expectedWeightKg;
        this.stockQty = stockQty;
    }

    public String getEpc() {
        return epc;
    }

    public String getItemName() {
        return itemName;
    }

    public String getSku() {
        return sku;
    }

    public double getExpectedWeightKg() {
        return expectedWeightKg;
    }

    public int getStockQty() {
        return stockQty;
    }
}
