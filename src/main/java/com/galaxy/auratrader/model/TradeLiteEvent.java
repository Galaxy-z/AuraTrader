package com.galaxy.auratrader.model;

import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.TradeLite;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight event model for "TradeLite" user-data events.
 *
 * Parsing strategy: convert the incoming object's toString() into key:value tokens
 * and map common Binance TradeLite fields (case-insensitive, strips "LowerCase" suffix).
 */
@Data
public class TradeLiteEvent {
    //{
    //"e":"TRADE_LITE", // 事件类型
    //"E":1721895408092, // 事件时间
    //"T":1721895408214, // 交易时间
    //"s":"BTCUSDT", // 交易对
    //"q":"0.001", // 订单原始数量
    //"p":"0", // 订单原始价格
    //"m":false, // 该成交是作为挂单成交吗？
    //"c":"z8hcUoOsqEdKMeKPSABslD", // 客户端自定订单ID
    /// / 特殊的自定义订单ID:
    /// / "autoclose-"开头的字符串: 系统强平订单
    /// / "adl_autoclose": ADL自动减仓订单
    /// / "settlement_autoclose-": 下架或交割的结算订单
    //"S":"BUY", // 订单方向
    //"L":"64089.20", // 订单末次成交价格
    //"l":"0.040", // 订单末次成交量
    //"t":109100866, // 成交ID
    //"i":8886774, // 订单ID
    //}

    private Date eventTime; // E
    private Date tradeTime; // T
    private String symbol; // s
    private BigDecimal origQty; // q
    private BigDecimal origPrice; // p
    private Boolean maker; // m
    private String clientOrderId; // c
    private String side; // S
    private BigDecimal lastPrice; // L
    private BigDecimal lastQty; // l
    private Long tradeId; // t
    private Long orderId; // i

    public TradeLiteEvent() {}

    public static TradeLiteEvent fromObject(TradeLite i) {
        if (i == null) return null;
        TradeLiteEvent e = new TradeLiteEvent();

        e.eventTime = i.getE() != null ? new Date(i.getE()) : null;
        e.tradeTime = i.getT() != null ? new Date(i.getT()) : null;
        e.symbol = i.getsLowerCase();
        e.origQty = i.getqLowerCase() != null ? new BigDecimal(i.getqLowerCase()) : null;
        e.origPrice = i.getpLowerCase() != null ? new BigDecimal(i.getpLowerCase()) : null;
        e.maker = i.getmLowerCase();
        e.clientOrderId = i.getcLowerCase();
        e.side = i.getS();
        e.lastPrice = i.getL() != null ? new BigDecimal(i.getL()) : null;
        e.lastQty = i.getlLowerCase() != null ? new BigDecimal(i.getlLowerCase()) : null;
        e.tradeId = i.gettLowerCase();
        e.orderId = i.getiLowerCase();
        return e;
    }


}
