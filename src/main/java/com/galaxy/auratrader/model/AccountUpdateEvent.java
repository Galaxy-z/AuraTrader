package com.galaxy.auratrader.model;

import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.AccountUpdate;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.AccountUpdateA;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.AccountUpdateABInner;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.AccountUpdateAPInner;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses ACCOUNT_UPDATE events from the websocket library's instance.toString() form.
 * Extracts top-level E/T and inner 'a' object containing B (balances) and P (positions).
 */
@Data
public class AccountUpdateEvent {
    //{
    //  "e": "ACCOUNT_UPDATE",				// 事件类型
    //  "E": 1564745798939,            		// 事件时间
    //  "T": 1564745798938 ,           		// 撮合时间
    //  "a":                          		// 账户更新事件
    //    {
    //      "m":"ORDER",						// 事件推出原因
    //      "B":[                     		// 余额信息
    //        {
    //          "a":"USDT",           		// 资产名称
    //          "wb":"122624.12345678",    	// 钱包余额
    //          "cw":"100.12345678",			// 除去逐仓仓位保证金的钱包余额
    //          "bc":"50.12345678"			// 除去盈亏与交易手续费以外的钱包余额改变量
    //        },
    //        {
    //          "a":"BUSD",
    //          "wb":"1.00000000",
    //          "cw":"0.00000000",
    //          "bc":"-49.12345678"
    //        }
    //      ],
    //      "P":[
    //       {
    //          "s":"BTCUSDT",          	// 交易对
    //          "pa":"0",               	// 仓位
    //          "ep":"0.00000",            // 入仓价格
    //          "bep":"0",                // 盈亏平衡价
    //          "cr":"200",             	// (费前)累计实现损益
    //          "up":"0",						// 持仓未实现盈亏
    //          "mt":"isolated",				// 保证金模式
    //          "iw":"0.00000000",			// 若为逐仓，仓位保证金
    //          "ps":"BOTH"					// 持仓方向
    //       }，
    //       {
    //        	"s":"BTCUSDT",
    //        	"pa":"20",
    //        	"ep":"6563.66500",
    //        	"bep":"6563.6",
    //        	"cr":"0",
    //        	"up":"2850.21200",
    //        	"mt":"isolated",
    //        	"iw":"13200.70726908",
    //        	"ps":"LONG"
    //      	 },
    //       {
    //        	"s":"BTCUSDT",
    //        	"pa":"-10",
    //        	"ep":"6563.86000",
    //        	"bep":"6563.6",
    //        	"cr":"-45.04000000",
    //        	"up":"-1423.15600",
    //        	"mt":"isolated",
    //        	"iw":"6570.42511771",
    //        	"ps":"SHORT"
    //       }
    //      ]
    //    }
    //}

    private Date eventTime; // E
    private Date matchTime; // T

    private String reason; // a.m

    @Data
    @Builder
    public static class Balance {
        private String asset; // a
        private BigDecimal walletBalance; // wb
        private BigDecimal crossWalletBalance; // cw
        private BigDecimal balanceChange; // bc

    }

    @Builder
    @Data
    public static class Position {
        private String symbol; // s
        private BigDecimal positionAmt; // pa
        private BigDecimal entryPrice; // ep
        private BigDecimal breakEvenPrice; // bep
        private BigDecimal realisedPnl; // cr
        private BigDecimal unrealizedPnl; // up
        private String marginType; // mt
        private BigDecimal isolatedWallet; // iw
        private String positionSide; // ps
    }

    private final List<Balance> balances = new ArrayList<>();
    private final List<Position> positions = new ArrayList<>();

    private final Map<String, String> raw = new HashMap<>();

    public AccountUpdateEvent() {}

    public static AccountUpdateEvent fromObject(AccountUpdate i) {
        if (i == null) return null;
        String s = i.toString();
        AccountUpdateEvent e = new AccountUpdateEvent();

        e.eventTime = i.getE() != null ? new Date(i.getE()) : null;
        e.matchTime = i.getT() != null ? new Date(i.getT()) : null;

        if (i.getaLowerCase()!=null){
            AccountUpdateA a = i.getaLowerCase();
            e.reason = a.getmLowerCase();
            if (a.getB() != null) {
                for (AccountUpdateABInner b : a.getB()) {
                    Balance bal = Balance.builder()
                            .asset(b.getaLowerCase())
                            .walletBalance(toBigDecimal(b.getWb()))
                            .crossWalletBalance(toBigDecimal(b.getCw()))
                            .balanceChange(toBigDecimal(b.getBc()))
                            .build();
                    e.balances.add(bal);
                }
            }
            if (a.getP() != null) {
                for (AccountUpdateAPInner p : a.getP()) {
                    Position pos = Position.builder()
                            .symbol(p.getsLowerCase())
                            .positionAmt(toBigDecimal(p.getPa()))
                            .entryPrice(toBigDecimal(p.getEp()))
                            .breakEvenPrice(toBigDecimal(p.getBep()))
                            .realisedPnl(toBigDecimal(p.getCr()))
                            .unrealizedPnl(toBigDecimal(p.getUp()))
                            .marginType(p.getMt())
                            .isolatedWallet(toBigDecimal(p.getIw()))
                            .positionSide(p.getPs())
                            .build();
                    e.positions.add(pos);
                }
            }
        }

        return e;
    }

    private static BigDecimal toBigDecimal(String s) {
        if (s == null) return null;
        try { return new BigDecimal(s); } catch (Exception ex) { return null; }
    }
}

