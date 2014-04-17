package com.englishtown.zenon;

import com.englishtown.zenon.ProductConfigInterface;

/**
 * Configuration related to the product flavor claro
 */
public enum ProductConfig implements ProductConfigInterface {
    INSTANCE;

    @Override
    public String getProductFlavorName() {
        return "zenon";
    }
}
