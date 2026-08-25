package com.lipi.serialreceiver.inventory;

import com.lipi.serialreceiver.model.InventoryItem;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Hardcoded lookup table: RFID tag (EPC) -> InventoryItem.
 *
 * This stands in for a real inventory backend/database. Replace {@link #buildCatalog()}
 * with a DB query or API call later; everything else in the app only talks to this
 * repository through {@link #findByEpc(String)}, so the swap is isolated to this file.
 */
public final class InventoryRepository {

    private static final Map<String, InventoryItem> CATALOG = buildCatalog();

    private InventoryRepository() {
    }

    private static Map<String, InventoryItem> buildCatalog() {
        Map<String, InventoryItem> map = new HashMap<>();

        // epc, item name, sku, expected weight (kg), stock qty
        put(map, "E28011B0A502006E7CD189E3", "Rice Bag 25kg", "SKU-1001", 25.000, 42);
        put(map, "E28011B0A502006E7CE9A95A", "Wheat Flour Bag 10kg", "SKU-1002", 10.000, 30);
        put(map, "E28011B0A502006E7CE9A90A", "Sugar Bag 5kg", "SKU-1003", 5.000, 60);
        put(map, "E28011B0A502006E7CE9A91A", "Cooking Oil Can 15L", "SKU-1004", 13.800, 18);
        put(map, "E28011B0A502006E7CE983EA", "Pulses Bag 10kg", "SKU-1005", 10.000, 25);
        put(map, "E28011B0A502006E7CE983FA", "Salt Bag 25kg", "SKU-1006", 25.000, 15);
        put(map, "E28011B0A502006E7CE983DA", "Basmati Rice 40kg", "SKU-1007", 40.000, 8);
        put(map, "E28011B0A502006E7CE9A93A", "Tea Powder Box 5kg", "SKU-1008", 5.000, 22);
        put(map, "E28011B0A502006E7CE9A94A", "Dal Bag 20kg", "SKU-1009", 20.000, 12);
        put(map, "E28011B0A502006E7CE9A92A", "Maida Bag 10kg", "SKU-1010", 10.000, 35);

        return Collections.unmodifiableMap(map);
    }

    private static void put(Map<String, InventoryItem> map, String epc, String name, String sku,
                             double expectedWeightKg, int stockQty) {
        map.put(normalize(epc), new InventoryItem(epc, name, sku, expectedWeightKg, stockQty));
    }

    /** Looks up an item by EPC. Returns null if the tag is not a known/registered item. */
    public static InventoryItem findByEpc(String epc) {
        if (epc == null) return null;
        return CATALOG.get(normalize(epc));
    }

    private static String normalize(String epc) {
        return epc == null ? null : epc.trim().toUpperCase();
    }
}
