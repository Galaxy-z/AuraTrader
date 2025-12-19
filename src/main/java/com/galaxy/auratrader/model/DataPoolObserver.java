package com.galaxy.auratrader.model;

public interface DataPoolObserver {
    void onDataUpdated(DataPool.DataType type);
}

