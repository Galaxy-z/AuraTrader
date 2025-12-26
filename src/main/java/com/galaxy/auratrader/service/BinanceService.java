package com.galaxy.auratrader.service;

import com.binance.connector.client.common.ApiResponse;
import com.binance.connector.client.common.websocket.service.StreamBlockingQueueWrapper;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.api.DerivativesTradingUsdsFuturesRestApi;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.*;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.api.api.DerivativesTradingUsdsFuturesWebSocketApi;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.api.model.*;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.api.model.StartUserDataStreamResponse;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.api.DerivativesTradingUsdsFuturesWebSocketStreams;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.galaxy.auratrader.config.BinanceProperties;
import com.galaxy.auratrader.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class BinanceService implements DisposableBean {


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

    // Account update stream state
    private StreamBlockingQueueWrapper<UserDataStreamEventsResponse> accountQueue;
    private Thread accountThread;
    private String accountListenKey;

    // Scheduler for keep-alive of user data stream (prevent listenKey from expiring)
    private final ScheduledExecutorService accountScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "account-keepalive-scheduler");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> accountKeepAliveFuture;


    public List<String> getPairs() {
        return binanceProperties.getPairs();
    }

    public List<KlineData> getKlineData(String symbol, String intervalStr) {
        return getKlineData(symbol, intervalStr, null, null, 100L);
    }

    /**
     * Fetch klines, optionally within a time range. If startTime/endTime are null the method
     * will fetch the latest `limit` candles and replace the cache. If a time range is provided
     * (for example when loading earlier data), the returned klines will be merged into the
     * existing cache: earlier candles will be prepended (avoid duplicates), and DataPool will be updated.
     *
     * @param symbol      trading pair symbol
     * @param intervalStr interval string like "1m","5m" etc.
     * @param startTime   optional start timestamp in ms (inclusive)
     * @param endTime     optional end timestamp in ms (inclusive)
     * @param limit       max number of candles to fetch
     * @return list of fetched klines (in ascending openTime order)
     */
    public List<KlineData> getKlineData(String symbol, String intervalStr, Long startTime, Long endTime, Long limit) {
        // 更新 DataPool 中的当前选择（保证其它 UI / 观察者能读取）
        DataPool.getInstance().setCurrentPair(symbol);
        DataPool.getInstance().setCurrentInterval(intervalStr);
        // Map string interval to Interval enum if necessary, or just pass string if library supports it.
        // The library uses Interval enum.
        Interval interval = Interval.valueOf("INTERVAL_" + intervalStr);

        ApiResponse<KlineCandlestickDataResponse> response = restApi.klineCandlestickData(
                symbol, interval, startTime, endTime, limit
        );
        KlineCandlestickDataResponse data = response.getData();

        List<KlineData> fetched = new ArrayList<>();
        for (KlineCandlestickDataResponseItem item : data) {
            fetched.add(parseKlineData(item));
        }

        // Ensure data is sorted ascending by openTime
        fetched.sort(Comparator.comparingLong(k -> k.getOpenTime().getTime()));

        // If no explicit time range provided, replace cache with fetched latest
        if (startTime == null && endTime == null) {
            synchronized (cachedKlineData) {
                cachedKlineData.clear();
                cachedKlineData.addAll(fetched);
            }
        } else {
            // Merge: if we're fetching earlier data (prepending), add unique earlier candles to head
            synchronized (cachedKlineData) {
                if (cachedKlineData.isEmpty()) {
                    cachedKlineData.addAll(fetched);
                } else {
                    long firstExisting = cachedKlineData.get(0).getOpenTime().getTime();
                    // collect toPrepend those with openTime < firstExisting
                    List<KlineData> toPrepend = new ArrayList<>();
                    for (KlineData k : fetched) {
                        long t = k.getOpenTime().getTime();
                        if (t < firstExisting) {
                            toPrepend.add(k);
                        }
                    }
                    // remove duplicates by timestamp
                    Set<Long> existingTimes = new HashSet<>();
                    for (KlineData k : cachedKlineData) existingTimes.add(k.getOpenTime().getTime());
                    List<KlineData> finalPrepend = new ArrayList<>();
                    for (KlineData k : toPrepend) {
                        if (!existingTimes.contains(k.getOpenTime().getTime())) {
                            finalPrepend.add(k);
                        }
                    }
                    if (!finalPrepend.isEmpty()) {
                        // prepend while keeping chronological order
                        List<KlineData> newCache = new ArrayList<>();
                        newCache.addAll(finalPrepend);
                        newCache.addAll(cachedKlineData);
                        cachedKlineData.clear();
                        cachedKlineData.addAll(newCache);
                    }
                }
            }
        }

        // Update data pool and recompute indicators
        List<KlineData> currentSnapshot = getCachedKlineData();
        dataPool.setKlineData(currentSnapshot); // 更新数据池
        try {
            var ind = indicatorService.computeIndicators(currentSnapshot, 20, 14);
            dataPool.setIndicators(ind);
        } catch (Exception e) {
            log.warn("Failed to compute indicators", e);
        }

        return fetched;
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
        ApiResponse<FuturesAccountBalanceV3Response> response = restApi.futuresAccountBalanceV3(binanceProperties.getRecvWindow());
        List<FuturesAccountBalanceV2ResponseInner> balances = response.getData();
        dataPool.setBalances(balances); // 更新数据池
        return balances;
    }

    // -------------------- All Orders --------------------

    //[
    //  {
    //    	"avgPrice": "0.00000",             // 平均成交价
    //    	"clientOrderId": "abc",            // 用户自定义的订单号
    //    		"cumQuote": "0",                   // 成交金额
    //    		"executedQty": "0",               // 成交量
    //    		"orderId": 1917641,                 // 系统订单号
    //    		"origQty": "0.40",                // 原始委托数量
    //    		"origType": "TRAILING_STOP_MARKET",// 触发前订单类型
    //    		"price": "0",                     // 委托价格
    //    		"reduceOnly": false,                // 是否仅减仓
    //    		"side": "BUY",                    // 买卖方向
    //    		"positionSide": "SHORT", // 持仓方向
    //    		"status": "NEW",                  // 订单状态
    //    		"stopPrice": "9300",              // 触发价，对`TRAILING_STOP_MARKET`无效
    //    		"closePosition": false,             // 是否条件全平仓
    //    		"symbol": "BTCUSDT",              // 交易对
    //    		"time": 1579276756075,              // 订单时间
    //    		"timeInForce": "GTC",             // 有效方法
    //    		"type": "TRAILING_STOP_MARKET",   // 订单类型
    //    		"activatePrice": "9020", // 跟踪止损激活价格, 仅`TRAILING_STOP_MARKET` 订单返回此字段
    //    		"priceRate": "0.3",   // 跟踪止损回调比例, 仅`TRAILING_STOP_MARKET` 订单返回此字段
    //    		"updateTime": 1579276756075,       // 更新时间
    //    		"workingType": "CONTRACT_PRICE", // 条件价格触发类型
    //    		"priceProtect": false,           // 是否开启条件单触发保护
    //    		"priceMatch": "NONE",              //盘口价格下单模式
    //    		"selfTradePreventionMode": "NONE", //订单自成交保护模式
    //    		"goodTillDate": 0      //订单TIF为GTD时的自动取消时间
    //  }
    //]
    public List<AllOrdersResponseInner> getAllOrders(String symbol) {
        ApiResponse<AllOrdersResponse> allOrdersResponseApiResponse = restApi.allOrders(symbol, null, null, null, null, binanceProperties.getRecvWindow());
        AllOrdersResponse allOrders = allOrdersResponseApiResponse.getData();
        List<AllOrdersResponseInner> list = new ArrayList<>();
        for (AllOrdersResponseInner allOrder : allOrders) {
            list.add(allOrder);
        }
        dataPool.setOrders(list); // 更新数据池
        return list;
    }



    // -------------------- Account Update Stream --------------------
    public void startAccountUpdateStream() {
        if (accountThread != null && accountThread.isAlive()) {
            log.info("Account update stream already running");
            return;
        }
        accountThread = new Thread(() -> {
            try {
                StartUserDataStreamRequest startUserDataStreamRequest = new StartUserDataStreamRequest();
                CompletableFuture<StartUserDataStreamResponse> future =
                        webSocketApi.startUserDataStream(startUserDataStreamRequest);
                StartUserDataStreamResponse response = future.join();
                log.info("Started user data stream: {}", response);
                accountListenKey = response.getResult().getListenKey();

                // schedule keep-alive every 50 minutes to prevent listenKey from expiring (expires in 60 minutes)
                try {
                    if (accountKeepAliveFuture != null && !accountKeepAliveFuture.isDone()) {
                        accountKeepAliveFuture.cancel(true);
                    }
                    accountKeepAliveFuture = accountScheduler.scheduleAtFixedRate(() -> {
                        try {
                            keepAliveAccountUpdateStream();
                        } catch (Exception e) {
                            log.warn("Keepalive task failed", e);
                        }
                    }, 50, 50, TimeUnit.MINUTES);
                } catch (Exception e) {
                    log.warn("Failed to schedule keepalive task", e);
                }

                accountQueue = webSocketStreams.userData(accountListenKey);
                while (!Thread.currentThread().isInterrupted()) {
                    UserDataStreamEventsResponse event = accountQueue.take();
                    if (event == null) continue;
                    Object actualInstance = event.getActualInstance();
                    if  (actualInstance == null) continue;
                    String title = "";
                    String message = "";
                    ObjectMapper mapper = new ObjectMapper();
                    switch (actualInstance.getClass().getSimpleName()) {
                        case "TradeLite":
                            // Build a typed TradeLiteEvent and add as a notification for UI
                            try {
                                TradeLiteEvent tle = TradeLiteEvent.fromObject((TradeLite)actualInstance);
                                title = "精简交易推送";
                                message = mapper.writeValueAsString(tle);
                                log.info("Received trade event: {} - {}", title, message);
                                Notification n = new Notification(new Date(), title, message);
                                dataPool.addNotification(n);
                            } catch (Exception ex) {
                                // Fallback: raw string
                                title = "精简交易推送";
                                message = actualInstance.toString();
                                log.warn("Failed to parse TradeLite instance, using raw string: {}", ex.getMessage());
                                Notification n = new Notification(new Date(), title, message);
                                dataPool.addNotification(n);
                            }
                             continue;
                        case "OrderTradeUpdate":
                            try {
                                OrderTradeUpdateEvent otue = OrderTradeUpdateEvent.fromObject((OrderTradeUpdate)actualInstance);
                                title = "订单交易更新";
                                message = mapper.writeValueAsString(otue);
                                log.info("Received order trade update: {} - {}", title, message);
                                Notification on = new Notification(new Date(), title, message);
                                dataPool.addNotification(on);
                            } catch (Exception ex) {
                                title = "订单交易更新";
                                message = actualInstance.toString();
                                log.warn("Failed to parse OrderTradeUpdate instance, using raw string: {}", ex.getMessage());
                                Notification on = new Notification(new Date(), title, message);
                                dataPool.addNotification(on);
                            }
                             continue;
                        case "AccountUpdate":
                            try {
                                AccountUpdateEvent aue = AccountUpdateEvent.fromObject((AccountUpdate)actualInstance);
                                title = "账户更新";
                                message = mapper.writeValueAsString(aue);
                                log.info("Received account update: {} - {}", title, message);
                                Notification an = new Notification(new Date(), title, message);
                                dataPool.addNotification(an);
                            } catch (Exception ex) {
                                title = "账户更新";
                                message = actualInstance.toString();
                                log.warn("Failed to parse AccountUpdate instance, using raw string: {}", ex.getMessage());
                                Notification an = new Notification(new Date(), title, message);
                                dataPool.addNotification(an);
                            }
                             continue;
                        case "AccountConfigUpdate":
                            try {
                                AccountConfigUpdate i = (AccountConfigUpdate)actualInstance;
                                AccountConfigUpdateAc ac = i.getAc();
                                String symbol = ac.getsLowerCase();
                                Long leverage = ac.getlLowerCase();
                                if (Objects.equals(dataPool.getCurrentPair(), symbol)) {
                                    dataPool.setLeverage(leverage); // 更新 DataPool 中的杠杆
                                }
                                title = "账户配置更新";
                                message = mapper.writeValueAsString(i);
                                log.info("Received account config update: {} - {}", title, message);
                                Notification acn = new Notification(new Date(), title, message);
                                dataPool.addNotification(acn);
                            } catch (Exception ex) {
                                title = "账户配置更新";
                                message = actualInstance.toString();
                                log.warn("Failed to parse AccountConfigUpdate instance, using raw string: {}", ex.getMessage());
                                Notification acn = new Notification(new Date(), title, message);
                                dataPool.addNotification(acn);
                            }
                             continue;

                        default:
                            // Fallback for unknown types
                            log.info("Received unknown event type: {}", actualInstance);
                            break;
                    }
                    // Ensure we have a title/message to display; fallback to schema or raw event
                    if (title.isEmpty()) {
                        String schema = event.getSchemaType();
                        title = schema != null ? schema : "User Data";
                        message = event.toString();
                    }
                    log.info("Received account event: {} - {}", title, message);
                    Notification n = new Notification(new Date(), title, message);
                    dataPool.addNotification(n);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.info("Account update stream interrupted");
            } catch (Exception e) {
                log.error("Error in account update stream", e);
            }
        }, "account-update-stream");
        accountThread.setDaemon(true);
        accountThread.start();
    }

    public void stopAccountUpdateStream() {
        if (accountThread != null && accountThread.isAlive()) {
            accountThread.interrupt();
        }
        accountThread = null;
        accountQueue = null;
        accountListenKey = null;
        // cancel scheduled keep-alive
        if (accountKeepAliveFuture != null && !accountKeepAliveFuture.isDone()) {
            accountKeepAliveFuture.cancel(true);
        }
        accountKeepAliveFuture = null;
    }

    public void keepAliveAccountUpdateStream() {
        if (accountListenKey == null) {
            log.warn("No account listen key to keep alive");
            return;
        }
        webSocketApi.keepaliveUserDataStream(new KeepaliveUserDataStreamRequest())
                .thenAccept(response -> log.info("Kept alive user data stream: {}", response))
                .exceptionally(ex -> {
                    log.error("Failed to keep alive user data stream", ex);
                    return null;
                });
    }

    public void closeAccountUpdateStream() {
        if (accountListenKey == null) {
            log.warn("No account listen key to close");
            return;
        }
        webSocketApi.closeUserDataStream(new CloseUserDataStreamRequest())
                .thenAccept(response -> log.info("Closed user data stream: {}", response))
                .exceptionally(ex -> {
                    log.error("Failed to close user data stream", ex);
                    return null;
                });
    }
    // -------------------- Positions --------------------
    public List<PositionInformationV3ResponseInner> getPositions(String symbol) {
        ApiResponse<PositionInformationV3Response> response = restApi.positionInformationV3(symbol, binanceProperties.getRecvWindow());
        List<PositionInformationV3ResponseInner> positions = response.getData();

        // Update DataPool so UI observers get notified
        try {
            dataPool.setPositions(positions);
        } catch (Exception e) {
            log.warn("Failed to set positions into DataPool", e);
        }

        return positions;
    }

    // -------------------- Symbol Configuration --------------------
    public com.binance.connector.client.derivatives_trading_usds_futures.rest.model.SymbolConfigurationResponseInner getSymbolConfiguration(String symbol) {
        ApiResponse<com.binance.connector.client.derivatives_trading_usds_futures.rest.model.SymbolConfigurationResponse> response = restApi.symbolConfiguration(symbol, binanceProperties.getRecvWindow());
        com.binance.connector.client.derivatives_trading_usds_futures.rest.model.SymbolConfigurationResponse data = response.getData();
        if (data == null) {
            log.info("Symbol configuration response was null for {}", symbol);
            dataPool.setLeverage(null);
            dataPool.setSymbolConfiguration(null);
            return null;
        }
        for (com.binance.connector.client.derivatives_trading_usds_futures.rest.model.SymbolConfigurationResponseInner symbolConfigurationResponseInner : data) {
            if (symbolConfigurationResponseInner.getSymbol().equalsIgnoreCase(symbol)) {
                Long leverage = symbolConfigurationResponseInner.getLeverage();
                dataPool.setLeverage(leverage); // 设置到DataPool
                // store the full symbol configuration for UI use
                dataPool.setSymbolConfiguration(symbolConfigurationResponseInner);
                return symbolConfigurationResponseInner;
            }
        }
        log.info("Symbol configuration for {}: {}", symbol, data);
        dataPool.setLeverage(null); // 如果没找到，设置为null
        dataPool.setSymbolConfiguration(null);
        return null;
    }

    // ---------------------- Commission Rate ----------------------
    public void getCommissionRate(String symbol) {
        ApiResponse<UserCommissionRateResponse> response = restApi.userCommissionRate(symbol, binanceProperties.getRecvWindow());
        UserCommissionRateResponse commissionRate = response.getData();
        log.info("Commission rate for {}: {}", symbol, commissionRate);
        // 同步到DataPool
        DataPool.getInstance().setCommissionRate(commissionRate);
    }

    public void getExchangeInfo(String symbol) {
        ApiResponse<ExchangeInformationResponse> exchangeInformationResponseApiResponse = restApi.exchangeInformation();
        ExchangeInformationResponse exchangeInformation = exchangeInformationResponseApiResponse.getData();
        // Store into DataPool for use by UI and other services
        try {
            DataPool.getInstance().setExchangeInfo(exchangeInformation);
            log.info("Fetched exchange info and stored in DataPool: {}", exchangeInformation != null ? "present" : "null");
        } catch (Exception e) {
            log.warn("Failed to store exchange info into DataPool", e);
        }

    }



    // -------------------- Shutdown --------------------

    public void shutdown() {
        // Attempt to close remote user data stream first
        try {
            if (accountListenKey != null) {
                closeAccountUpdateStream();
            }
        } catch (Exception e) {
            log.warn("Failed to call closeAccountUpdateStream during shutdown", e);
        }

        // Stop local stream/thread and cancel scheduled task
        try {
            stopAccountUpdateStream();
        } catch (Exception e) {
            log.warn("Failed to stop account update stream during shutdown", e);
        }

        // cancel scheduled keep-alive if any
        if (accountKeepAliveFuture != null && !accountKeepAliveFuture.isDone()) {
            accountKeepAliveFuture.cancel(true);
        }
        try {
            accountScheduler.shutdownNow();
        } catch (Exception e) {
            log.warn("Failed to shutdown account scheduler cleanly", e);
        }
        log.info("Shutting down account update stream");
    }

    @Override
    public void destroy() {
        shutdown();
    }

    /**
     * Fetch earlier klines before current cached earliest candle.
     * This computes a time range of `count` candles ending just before the earliest cached candle
     * (or now if cache empty) and calls getKlineData with that range. The fetched candles will be
     * prepended into the cache by getKlineData's merge logic.
     *
     * @param symbol trading pair
     * @param intervalStr interval string like "1m"
     * @param count number of candles to fetch
     * @return list of fetched klines (ascending)
     */
    public List<KlineData> fetchEarlierKlines(String symbol, String intervalStr, int count) {
        long endTime;
        synchronized (cachedKlineData) {
            if (cachedKlineData.isEmpty()) {
                endTime = System.currentTimeMillis();
            } else {
                endTime = cachedKlineData.get(0).getOpenTime().getTime() - 1L;
            }
        }
        long intervalMs = intervalToMillis(intervalStr);
        long startTime = endTime - (long) count * intervalMs;
        // Delegate to getKlineData which will merge/prepend
        return getKlineData(symbol, intervalStr, startTime, endTime, (long) count);
    }
}
