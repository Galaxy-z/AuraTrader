package com.galaxy.auratrader.service;

import com.binance.connector.client.common.ApiResponse;
import com.binance.connector.client.common.websocket.service.StreamBlockingQueueWrapper;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.api.DerivativesTradingUsdsFuturesRestApi;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.*;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.api.api.DerivativesTradingUsdsFuturesWebSocketApi;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.api.model.StartUserDataStreamRequest;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.api.model.StartUserDataStreamResponse;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.api.DerivativesTradingUsdsFuturesWebSocketStreams;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.KlineCandlestickStreamsRequest;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.KlineCandlestickStreamsResponse;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.UserDataStreamEventsResponse;
import com.galaxy.auratrader.config.BinanceProperties;
import com.galaxy.auratrader.model.DataPool;
import com.galaxy.auratrader.model.KlineData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
@RequiredArgsConstructor
public class BinanceService {


    private final DerivativesTradingUsdsFuturesRestApi restApi;
    private final DerivativesTradingUsdsFuturesWebSocketStreams webSocketStreams;
    private final DerivativesTradingUsdsFuturesWebSocketApi webSocketApi;
    private final BinanceProperties binanceProperties;
    private final IndicatorService indicatorService;

    private StreamBlockingQueueWrapper<KlineCandlestickStreamsResponse> currentStream;
    private Thread currentStreamThread;
    private final List<KlineData> cachedKlineData = Collections.synchronizedList(new ArrayList<>());
    private String currentSymbol;
    private String currentInterval;

    // 新增 DataPool 单例
    private final DataPool dataPool = DataPool.getInstance();


    public List<String> getPairs() {
        return binanceProperties.getPairs();
    }

    public List<KlineData> getKlineData(String symbol, String intervalStr) {
        // 更新 DataPool 中的当前选择（保证其它 UI / 观察者能读取）
        DataPool.getInstance().setCurrentPair(symbol);
        DataPool.getInstance().setCurrentInterval(intervalStr);
        // Map string interval to Interval enum if necessary, or just pass string if library supports it.
        // The library uses Interval enum.
        Interval interval = Interval.valueOf("INTERVAL_" + intervalStr);

        ApiResponse<KlineCandlestickDataResponse> response = restApi.klineCandlestickData(
                symbol, interval, null, null, 50L
        );
        KlineCandlestickDataResponse data = response.getData();

        List<KlineData> klineDataList = new ArrayList<>();
        for (KlineCandlestickDataResponseItem item : data) {
            klineDataList.add(parseKlineData(item));
        }

        // Update cache
        synchronized (cachedKlineData) {
            cachedKlineData.clear();
            cachedKlineData.addAll(klineDataList);
        }
        dataPool.setKlineData(klineDataList); // 更新数据池
        // Compute indicators and update data pool so UI can read index data from DataPool
        try {
            var ind = indicatorService.computeIndicators(klineDataList, 20, 14);
            dataPool.setIndicators(ind);
        } catch (Exception e) {
            log.warn("Failed to compute indicators", e);
        }
        return klineDataList;
    }

    private KlineData parseKlineData(KlineCandlestickDataResponseItem item) {
        Date openTime = new Date(Long.parseLong(item.get(0)));
        BigDecimal open = new BigDecimal(item.get(1));
        BigDecimal high = new BigDecimal(item.get(2));
        BigDecimal low = new BigDecimal(item.get(3));
        BigDecimal close = new BigDecimal(item.get(4));
        BigDecimal volume = new BigDecimal(item.get(5));
        Date closeTime = new Date(Long.parseLong(item.get(6)));

        return new KlineData(openTime, open, high, low, close, volume, closeTime);
    }

    public List<KlineData> getCachedKlineData() {
        synchronized (cachedKlineData) {
            return new ArrayList<>(cachedKlineData);
        }
    }

    public void startStreaming(String symbol, String interval) {
        log.info("Starting streaming for {} at interval {}", symbol, interval);
        stopStreaming();
        this.currentSymbol = symbol;
        this.currentInterval = interval;

        // 同步 DataPool 的当前选择
        DataPool.getInstance().setCurrentPair(symbol);
        DataPool.getInstance().setCurrentInterval(interval);

        KlineCandlestickStreamsRequest request = new KlineCandlestickStreamsRequest()
                .symbol(symbol.toLowerCase()).interval(interval);
        currentStream = webSocketStreams.klineCandlestickStreams(request);

        currentStreamThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    KlineCandlestickStreamsResponse response = currentStream.take();
                    var k = response.getkLowerCase();
                    Date openTime = new Date(k.gettLowerCase());
                    BigDecimal open = new BigDecimal(k.getoLowerCase());
                    BigDecimal high = new BigDecimal(k.gethLowerCase());
                    BigDecimal low = new BigDecimal(k.getlLowerCase());
                    BigDecimal close = new BigDecimal(k.getcLowerCase());
                    BigDecimal volume = new BigDecimal(k.getvLowerCase());
                    Date closeTime = new Date(k.getT());

                    KlineData latest = new KlineData(openTime, open, high, low, close, volume, closeTime);
//                    log.info("Received Kline: {}", latest);
                    updateCache(latest);

                    // 只推送到数据池
                    List<KlineData> current = getCachedKlineData();
                    dataPool.setKlineData(current);
                    // recompute indicators and update pool
                    try {
                        var ind = indicatorService.computeIndicators(current, 20, 14);
                        dataPool.setIndicators(ind);
                    } catch (Exception e) {
                        log.warn("Failed to compute indicators during streaming", e);
                    }
                }
            } catch (InterruptedException e) {
                log.info("Streaming stopped");
            } catch (Exception e) {
                log.error("Error in streaming", e);
            }
        });
        currentStreamThread.start();
    }

    public void stopStreaming() {
        if (currentStreamThread != null && currentStreamThread.isAlive()) {
            currentStreamThread.interrupt();
        }
        // Note: The library might need explicit closing of the stream if supported,
        // but interrupting the consumer thread is often how these wrappers are handled if they block on take().
        // However, we should check if webSocketStreams has a close method for the specific stream.
        // The wrapper doesn't seem to have a close, but the underlying connection might.
        // For now, we just stop consuming.
    }

    private void updateCache(KlineData latestKline) {
        synchronized (cachedKlineData) {
            if (cachedKlineData.isEmpty()) {
                cachedKlineData.add(latestKline);
            } else {
                KlineData last = cachedKlineData.get(cachedKlineData.size() - 1);
                long lastTime = last.getOpenTime().getTime();
                long newTime = latestKline.getOpenTime().getTime();

                if (lastTime == newTime) {
                    // Update the last one
                    cachedKlineData.set(cachedKlineData.size() - 1, latestKline);
                } else if (newTime > lastTime) {
                    // Check for gap
                    long intervalMillis = intervalToMillis(currentInterval);
                    if (newTime > lastTime + intervalMillis) {
                        log.info("Gap detected: last={}, new={}. Fetching missing data...", last.getOpenTime(), latestKline.getOpenTime());
                        fillGap(lastTime + intervalMillis, newTime - 1);
                    }
                    // Append new
                    cachedKlineData.add(latestKline);
                }
            }
        }
    }

    private void fillGap(long startTime, long endTime) {
        try {
            ApiResponse<KlineCandlestickDataResponse> response = restApi.klineCandlestickData(
                    currentSymbol, Interval.valueOf("INTERVAL_" + currentInterval), startTime, endTime, 3L
            );
            KlineCandlestickDataResponse data = response.getData();
            for (KlineCandlestickDataResponseItem item : data) {
                KlineData kline = parseKlineData(item);
                // Double check to avoid duplicates if any
                if (cachedKlineData.isEmpty() || kline.getOpenTime().getTime() > cachedKlineData.get(cachedKlineData.size() - 1).getOpenTime().getTime()) {
                    log.info("Filling missing Kline: {}", kline);
                    cachedKlineData.add(kline);
                }
            }
        } catch (Exception e) {
            log.error("Failed to fill gap", e);
        }
    }

    private long intervalToMillis(String interval) {
        switch (interval) {
            case "1m":
                return 60 * 1000;
            case "5m":
                return 5 * 60 * 1000;
            case "15m":
                return 15 * 60 * 1000;
            case "1h":
                return 60 * 60 * 1000;
            case "4h":
                return 4 * 60 * 60 * 1000;
            case "1d":
                return 24 * 60 * 60 * 1000;
            default:
                return 60 * 1000;
        }
    }

    // ------------------- Account Balance ------------------
    public List<FuturesAccountBalanceV2ResponseInner> getAccountBalance() {
        ApiResponse<FuturesAccountBalanceV3Response> response = restApi.futuresAccountBalanceV3(5000L);
        List<FuturesAccountBalanceV2ResponseInner> balances = response.getData();
        dataPool.setBalances(balances); // 更新数据池
        return balances;
    }

    // -------------------- All Orders --------------------

    //[
    //  {
    //    	"avgPrice": "0.00000",             // 平均成交价
    //    	"clientOrderId": "abc",            // 用户自定义的订单号
    //    	"cumQuote": "0",                   // 成交金额
    //    	"executedQty": "0",               // 成交量
    //    	"orderId": 1917641,                 // 系统订单号
    //    	"origQty": "0.40",                // 原始委托数量
    //    	"origType": "TRAILING_STOP_MARKET",// 触发前订单类型
    //    	"price": "0",                     // 委托价格
    //    	"reduceOnly": false,                // 是否仅减仓
    //    	"side": "BUY",                    // 买卖方向
    //    	"positionSide": "SHORT", // 持仓方向
    //    	"status": "NEW",                  // 订单状态
    //    	"stopPrice": "9300",              // 触发价，对`TRAILING_STOP_MARKET`无效
    //    	"closePosition": false,             // 是否条件全平仓
    //    	"symbol": "BTCUSDT",              // 交易对
    //    	"time": 1579276756075,              // 订单时间
    //    	"timeInForce": "GTC",             // 有效方法
    //    	"type": "TRAILING_STOP_MARKET",   // 订单类型
    //    	"activatePrice": "9020", // 跟踪止损激活价格, 仅`TRAILING_STOP_MARKET` 订单返回此字段
    //    	"priceRate": "0.3",   // 跟踪止损回调比例, 仅`TRAILING_STOP_MARKET` 订单返回此字段
    //    	"updateTime": 1579276756075,       // 更新时间
    //    	"workingType": "CONTRACT_PRICE", // 条件价格触发类型
    //   	"priceProtect": false,           // 是否开启条件单触发保护
    //   	"priceMatch": "NONE",              //盘口价格下单模式
    //   	"selfTradePreventionMode": "NONE", //订单自成交保护模式
    //   	"goodTillDate": 0      //订单TIF为GTD时的自动取消时间
    //   }
    //]
    public List<AllOrdersResponseInner> getAllOrders(String symbol) {
        ApiResponse<AllOrdersResponse> allOrdersResponseApiResponse = restApi.allOrders(symbol, null, null, null, null, 500L);
        AllOrdersResponse allOrders = allOrdersResponseApiResponse.getData();
        List<AllOrdersResponseInner> list = new ArrayList<>();
        for (AllOrdersResponseInner allOrder : allOrders) {
            list.add(allOrder);
        }
        dataPool.setOrders(list); // 更新数据池
        return list;
    }


    // -------------------- Account Update Stream --------------------
    public void accountUpdateStream() {

        StartUserDataStreamRequest startUserDataStreamRequest = new StartUserDataStreamRequest();
        CompletableFuture<StartUserDataStreamResponse> future =
                webSocketApi.startUserDataStream(startUserDataStreamRequest);
        StartUserDataStreamResponse response = future.join();
        System.out.println(response);
        String listenKey = response.getResult().getListenKey();

        StreamBlockingQueueWrapper<UserDataStreamEventsResponse> queueWrapper = webSocketStreams.userData(listenKey);
        while (true) {
            try {
                UserDataStreamEventsResponse event = queueWrapper.take();
//                System.out.println("Received user data event: " + event);
                switch (event.getSchemaType()){
                    case "ACCOUNT_UPDATE":
                        System.out.println("Account Update Event: " + event.getAccountUpdate());
                        break;
                    case "ORDER_TRADE_UPDATE":
                        System.out.println("Order Trade Update Event: " + event.getOrderTradeUpdate());
                        break;
                    default:
                        System.out.println("Other Event Type: " + event.getSchemaType());
                }



            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

    }

}
