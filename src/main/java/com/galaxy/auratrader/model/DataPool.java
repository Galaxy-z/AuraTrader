package com.galaxy.auratrader.model;

import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.AllOrdersResponseInner;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.FuturesAccountBalanceV2ResponseInner;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.PositionInformationV3ResponseInner;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.UserCommissionRateResponse;
import com.galaxy.auratrader.service.IndicatorService.IndicatorResult;
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
    @Getter
    private IndicatorResult indicators = new IndicatorResult();

    @Getter
    private String currentPair = "";
    @Getter
    private String currentInterval = "";

    // 新增：订单列表
    @Getter
    private List<AllOrdersResponseInner> orders = Collections.emptyList();

    // 新增：通知历史
    @Getter
    private List<Notification> notifications = Collections.emptyList();

    // 新增：持仓列表（Positions）
    @Getter
    private List<PositionInformationV3ResponseInner> positions = Collections.emptyList();

    // 新增：杠杆率
    @Getter
    private Long leverage;

    // 新增：手续费率
    @Getter
    private UserCommissionRateResponse commissionRate; // 可根据UserCommissionRateResponse类型替换Object

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

    public void setIndicators(IndicatorResult indicators) {
        this.indicators = indicators != null ? indicators : new IndicatorResult();
        notifyObservers(DataType.INDICATOR);
    }

    public void setCurrentPair(String pair) {
        this.currentPair = pair != null ? pair : "";
        notifyObservers(DataType.PAIR);
    }

    public void setCurrentInterval(String interval) {
        this.currentInterval = interval != null ? interval : "";
        notifyObservers(DataType.INTERVAL);
    }

    // 新增：设置订单并通知
    public void setOrders(List<AllOrdersResponseInner> orders) {
        this.orders = orders != null ? orders : Collections.emptyList();
        notifyObservers(DataType.ORDERS);
    }

    // 新增：设置持仓并通知
    public void setPositions(List<PositionInformationV3ResponseInner> positions) {
        this.positions = positions != null ? positions : Collections.emptyList();
        notifyObservers(DataType.POSITIONS);
    }

    // 新增：设置杠杆率并通知
    public void setLeverage(Long leverage) {
        this.leverage = leverage;
        notifyObservers(DataType.LEVERAGE);
    }

    // 新增：设置手续费率并通知
    public void setCommissionRate(UserCommissionRateResponse commissionRate) {
        this.commissionRate = commissionRate;
        notifyObservers(DataType.COMMISSION_RATE);
    }

    // 新增：设置或追加通知
    public void setNotifications(List<Notification> notifications) {
        this.notifications = notifications != null ? notifications : Collections.emptyList();
        notifyObservers(DataType.NOTIFICATIONS);
    }

    public void addNotification(Notification notification) {
        if (notification == null) return;
        // Use a copy-on-write strategy: create a new list with the new notification appended
        List<Notification> newList = new java.util.ArrayList<>(this.notifications);
        newList.add(0, notification); // newest first
        this.notifications = java.util.Collections.unmodifiableList(newList);
        notifyObservers(DataType.NOTIFICATIONS);
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
        KLINE, BALANCE, INDICATOR, PAIR, INTERVAL, ORDERS, NOTIFICATIONS, POSITIONS, LEVERAGE, COMMISSION_RATE
    }
}
