package com.lipi.serialreceiver.inventory;

import com.lipi.serialreceiver.model.JewelleryItem;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class JewelleryRepository {

    private static final Map<String, JewelleryItem> ITEMS =
            buildCatalog();

    private static Map<String, JewelleryItem> buildCatalog() {

        Map<String, JewelleryItem> map = new HashMap<>();

        map.put(
                "E28011B0A502006E7CD189E3",
                new JewelleryItem(
                        "E28011B0A502006E7CD189E3",
                        "Gold Ring - Classic",
                        "Ring",
                        "Gold",
                        48.636,
                        "22K"
                )
        );

        map.put(
                "E28011B0A502006E7CE9A95A",
                new JewelleryItem(
                        "E28011B0A502006E7CE9A95A",
                        "Gold Chain - Royal",
                        "Chain",
                        "Gold",
                        32.450,
                        "22K"
                )
        );

        map.put(
                "E28011B0A502006E7CE9A90A",
                new JewelleryItem(
                        "E28011B0A502006E7CE9A90A",
                        "Gold Bracelet - Classic",
                        "Bracelet",
                        "Gold",
                        75.210,
                        "22K"
                )
        );

        map.put(
                "E28011B0A502006E7CE9A91A",
                new JewelleryItem(
                        "E28011B0A502006E7CE9A91A",
                        "Gold Necklace - Designer",
                        "Necklace",
                        "Gold",
                        84.250,
                        "22K"
                )
        );

        map.put(
                "E28011B0A502006E7CE983EA",
                new JewelleryItem(
                        "E28011B0A502006E7CE983EA",
                        "Gold Pendant - Round",
                        "Pendant",
                        "Gold",
                        18.450,
                        "18K"
                )
        );

        map.put(
                "E28011B0A502006E7CE983FA",
                new JewelleryItem(
                        "E28011B0A502006E7CE983FA",
                        "Gold Earrings - Drop",
                        "Earrings",
                        "Gold",
                        12.680,
                        "22K"
                )
        );

        map.put(
                "E28011B0A502006E7CE983DA",
                new JewelleryItem(
                        "E28011B0A502006E7CE983DA",
                        "Gold Ring - Diamond",
                        "Ring",
                        "Gold",
                        27.320,
                        "22K"
                )
        );

        map.put(
                "E28011B0A502006E7CE9A93A",
                new JewelleryItem(
                        "E28011B0A502006E7CE9A93A",
                        "Gold Bracelet - Designer",
                        "Bracelet",
                        "Gold",
                        54.910,
                        "22K"
                )
        );

        map.put(
                "E28011B0A502006E7CE9A94A",
                new JewelleryItem(
                        "E28011B0A502006E7CE9A94A",
                        "Gold Chain - Light",
                        "Chain",
                        "Gold",
                        41.275,
                        "22K"
                )
        );

        map.put(
                "E28011B0A502006E7CE9A92A",
                new JewelleryItem(
                        "E28011B0A502006E7CE9A92A",
                        "Gold Pendant - Diamond",
                        "Pendant",
                        "Gold",
                        22.840,
                        "22K"
                )
        );

        return Collections.unmodifiableMap(map);
    }

    public static JewelleryItem findByEpc(String epc) {
        if (epc == null) {
            return null;
        }

        return ITEMS.get(epc.trim().toUpperCase());
    }
}