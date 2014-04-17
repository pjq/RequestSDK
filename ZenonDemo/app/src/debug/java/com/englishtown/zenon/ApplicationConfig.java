package com.englishtown.zenon;

public enum ApplicationConfig implements MyApplicationConfigInterface {
    INSTANCE;

    public boolean DEBUG() {
        return true;
    }

    public boolean DEBUG_LOG() {
        return true;
    }

    public boolean API_DEV() {
        return false;
    }
}
