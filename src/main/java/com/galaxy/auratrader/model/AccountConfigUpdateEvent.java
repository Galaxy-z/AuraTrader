package com.galaxy.auratrader.model;

import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.AccountConfigUpdate;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.AccountUpdate;
import lombok.Data;

import java.util.Date;

public class AccountConfigUpdateEvent {
    //{
    //    "e":"ACCOUNT_CONFIG_UPDATE",       // 事件类型
    //    "E":1611646737479,		           // 事件时间
    //    "T":1611646737476,		           // 撮合时间
    //    "ac":{
    //    "s":"BTCUSDT",					   // 交易对
    //    "l":25						       // 杠杆倍数
    //    }
    //}
    private Date eventTime; // E
    private Date matchTime; // T

    @Data
    public static class AccountConfig {
        private String symbol; // s
        private Long leverage; // l
    }

    private AccountConfig ac; // ac

    public static AccountConfigUpdateEvent fromObject(AccountConfigUpdate i) {
        if (i == null) return null;
        AccountConfigUpdateEvent e = new AccountConfigUpdateEvent();
        e.eventTime = i.getE() != null ? new Date(i.getE()) : null;
        e.matchTime = i.getT() != null ? new Date(i.getT()) : null;
        if (i.getAc() != null) {
            AccountConfig ac = new AccountConfig();
            ac.setSymbol(i.getAc().getsLowerCase());
            ac.setLeverage(i.getAc().getlLowerCase());
            e.ac = ac;
        }
        return e;
    }



}
