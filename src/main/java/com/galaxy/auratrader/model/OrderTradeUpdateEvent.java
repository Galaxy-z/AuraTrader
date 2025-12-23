package com.galaxy.auratrader.model;

import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.OrderTradeUpdate;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.OrderTradeUpdateO;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Model for ORDER_TRADE_UPDATE events. Parses the instance.toString() produced by the websocket
 * library which has a nested `o` object (order details). The parser extracts top-level E/T and
 * the fields inside the order object.
 */
@Data
public class OrderTradeUpdateEvent {
    //{
    //  "e":"ORDER_TRADE_UPDATE",			// 事件类型
    //  "E":1568879465651,				    // 事件时间
    //  "T":1568879465650,				    // 撮合时间
    //  "o":{
    //    "s":"BTCUSDT",					    // 交易对
    //    "c":"TEST",						      // 客户端自定订单ID
    //      // 特殊的自定义订单ID:
    //      // "autoclose-"开头的字符串: 系统强平订单
    //      // "adl_autoclose": ADL自动减仓订单
    //      // "settlement_autoclose-": 下架或交割的结算订单
    //    "S":"SELL",						      // 订单方向
    //    "o":"TRAILING_STOP_MARKET",	// 订单类型
    //    "f":"GTC",						      // 有效方式
    //    "q":"0.001",					      // 订单原始数量
    //    "p":"0",						        // 订单原始价格
    //    "ap":"0",						        // 订单平均价格
    //    "sp":"7103.04",			        // 条件订单触发价格，对追踪止损单无效
    //    "x":"NEW",						      // 本次事件的具体执行类型
    //    "X":"NEW",						      // 订单的当前状态
    //    "i":8886774,					      // 订单ID
    //    "l":"0",						        // 订单末次成交量
    //    "z":"0",						        // 订单累计已成交量
    //    "L":"0",						        // 订单末次成交价格
    //    "N": "USDT",                // 手续费资产类型
    //    "n": "0",                   // 手续费数量
    //    "T":1568879465650,				  // 成交时间
    //    "t":0,							        // 成交ID
    //    "b":"0",						        // 买单净值
    //    "a":"9.91",						      // 卖单净值
    //    "m": false,					        // 该成交是作为挂单成交吗？
    //    "R":false	,				          // 是否是只减仓单
    //    "wt": "CONTRACT_PRICE",	    // 触发价类型
    //    "ot": "TRAILING_STOP_MARKET",	// 原始订单类型
    //    "ps":"LONG"						      // 持仓方向
    //    "cp":false,						      // 是否为触发平仓单; 仅在条件订单情况下会推送此字段
    //    "AP":"7476.89",					    // 追踪止损激活价格, 仅在追踪止损单时会推送此字段
    //    "cr":"5.0",						      // 追踪止损回调比例, 仅在追踪止损单时会推送此字段
    //    "pP": false,                // 是否开启条件单触发保护
    //    "si": 0,                    // 忽略
    //    "ss": 0,                    // 忽略
    //    "rp":"0",					          // 该交易实现盈亏
    //    "V":"EXPIRE_TAKER",         // 自成交防止模式
    //    "pm":"OPPONENT",            // 价格匹配模式
    //    "gtd":0,                    // TIF为GTD的订单自动取消时间
    //    "er":"0"                    // 过期原因
    //  }
    //}

    // Top-level
    private Date eventTime; // E
    private Date matchTime; // T (top-level)

    // Inner order fields (from o)
    private String symbol; // s
    private String clientOrderId; // c
    private String side; // S
    private String orderType; // o
    private String timeInForce; // f
    private BigDecimal origQty; // q
    private BigDecimal origPrice; // p
    private BigDecimal avgPrice; // ap
    private BigDecimal stopPrice; // sp
    private String executionType; // x
    private String status; // X
    private Long orderId; // i
    private BigDecimal lastQty; // l
    private BigDecimal cumQty; // z
    private BigDecimal lastPrice; // L
    private String feeAsset; // N
    private BigDecimal fee; // n
    private Date tradeTime; // T (inner)
    private Long tradeId; // t
    private BigDecimal bidNotional; // b
    private BigDecimal askNotional; // a
    private Boolean maker; // m
    private Boolean reduceOnly; // R
    private String triggerType; // wt
    private String origType; // ot
    private String positionSide; // ps
    private Boolean closePosition; // cp
    private BigDecimal activatePrice; // AP
    private BigDecimal callbackRate; // cr
    private Boolean priceProtect; // pP
    private BigDecimal realisedPnl; // rp
    private String selfTradePrevention; // V
    private String priceMatch; // pm
    private Long gtd; // gtd
    private String er; // er

    private final Map<String, String> raw = new HashMap<>();

    public OrderTradeUpdateEvent() {}

    public static OrderTradeUpdateEvent fromObject(OrderTradeUpdate i) {
        if (i == null) return null;
        OrderTradeUpdateEvent e = new OrderTradeUpdateEvent();

        e.eventTime = i.getE() != null ? new Date(i.getE()) : null;
        e.matchTime = i.getT() != null ? new Date(i.getT()) : null;

        OrderTradeUpdateO o = i.getoLowerCase();
        if (o != null) {
            e.symbol = o.getsLowerCase();
            e.clientOrderId = o.getcLowerCase();
            e.side = o.getS();
            e.orderType = o.getoLowerCase();
            e.timeInForce = o.getfLowerCase();
            e.origQty = toBigDecimal(o.getqLowerCase());
            e.origPrice = toBigDecimal(o.getpLowerCase());
            e.avgPrice = toBigDecimal(o.getAp());
            e.stopPrice = toBigDecimal(o.getSp());
            e.executionType = o.getxLowerCase();
            e.status = o.getX();
            e.orderId = o.getiLowerCase();
            e.lastQty = toBigDecimal(o.getlLowerCase());
            e.cumQty = toBigDecimal(o.getzLowerCase());
            e.lastPrice = toBigDecimal(o.getL());
            e.feeAsset = o.getN();
            e.fee = toBigDecimal(o.getnLowerCase());
            e.tradeTime = o.getT() != null ? new Date(o.getT()) : null;
            e.tradeId = o.gettLowerCase();
            e.bidNotional = toBigDecimal(o.getbLowerCase());
            e.askNotional = toBigDecimal(o.getaLowerCase());
            e.maker = o.getmLowerCase();
            e.reduceOnly = o.getR();
            e.triggerType = o.getWt();
            e.origType = o.getOt();
            e.positionSide = o.getPs();
            e.closePosition = o.getCp();
            e.activatePrice = toBigDecimal(o.getAP());
            e.callbackRate = toBigDecimal(o.getCr());
            e.priceProtect = o.getpP();
            e.realisedPnl = toBigDecimal(o.getRp());
            e.selfTradePrevention = o.getV();
            e.priceMatch = o.getPm();
            e.gtd = o.getGtd();
            e.er = o.getEr();
        }

        return e;
    }

    private static Map<String, String> parseKeyValues(String text) {
        Map<String, String> map = new HashMap<>();
        Pattern p = Pattern.compile("(\\w+):\\s*([^\\s{}]+)");
        Matcher m = p.matcher(text);
        while (m.find()) {
            map.put(m.group(1), m.group(2));
        }
        return map;
    }

    private static Long toLong(String s) {
        if (s == null) return null;
        try { return Long.parseLong(s); } catch (Exception ex) { return null; }
    }

    private static BigDecimal toBigDecimal(String s) {
        if (s == null) return null;
        try { return new BigDecimal(s); } catch (Exception ex) { return null; }
    }

    private static Boolean toBoolean(String s) {
        if (s == null) return null;
        try { return Boolean.parseBoolean(s); } catch (Exception ex) { return null; }
    }


}
