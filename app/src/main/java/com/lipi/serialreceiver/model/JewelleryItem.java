package com.lipi.serialreceiver.model;

public class JewelleryItem {

    private final String epc;
    private final String name;
    private final String type;
    private final String material;
    private final double expectedWeightGrams;
    private final String purity;
    private final String imageName;

    public JewelleryItem(
            String epc,
            String name,
            String type,
            String material,
            double expectedWeightGrams,
            String purity,
            String imageName) {

        this.epc = epc;
        this.name = name;
        this.type = type;
        this.material = material;
        this.expectedWeightGrams = expectedWeightGrams;
        this.purity = purity;
        this.imageName = imageName;
    }

    public String getEpc() {
        return epc;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getMaterial() {
        return material;
    }

    public double getExpectedWeightGrams() {
        return expectedWeightGrams;
    }

    public String getPurity() {
        return purity;
    }

    public String getImageName() {
        return imageName;
    }
}