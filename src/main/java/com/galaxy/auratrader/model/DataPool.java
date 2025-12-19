package com.galaxy.auratrader.model;

import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.FuturesAccountBalanceV2ResponseInner;
import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class DataPool {
    private static final DataPool INSTANCE = new DataPool();

    private final List<DataPoolObserver> observers = new CopyOnWriteArrayList<>();
    @Getter
    private List<KlineData> klineData = Collections.emptyList();
    @Getter
    private List<FuturesAccountBalanceV2ResponseInner> balances = Collections.emptyList();

    private DataPool() {}

    public static DataPool getInstance() {
        return INSTANCE;
    }

    public void setKlineData(List<KlineData> data) {
        this.klineData = data != null ? data : Collections.emptyList();
        notifyObservers(DataType.KLINE);
    }

    public void setBalances(List<FuturesAccountBalanceV2ResponseInner> balances) {
        this.balances = balances != null ? balances : Collections.emptyList();
        notifyObservers(DataType.BALANCE);
    }

    public void addObserver(DataPoolObserver observer) {
        observers.add(observer);
    }

    public void removeObserver(DataPoolObserver observer) {
        observers.remove(observer);
    }

    private void notifyObservers(DataType type) {
        for (DataPoolObserver observer : observers) {
            observer.onDataUpdated(type);
        }
    }

    public enum DataType {
        KLINE, BALANCE
    }
}
