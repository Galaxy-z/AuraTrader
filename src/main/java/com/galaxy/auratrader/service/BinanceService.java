package com.galaxy.auratrader.service;

import com.binance.connector.client.common.ApiResponse;
import com.binance.connector.client.common.websocket.service.StreamBlockingQueueWrapper;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.api.DerivativesTradingUsdsFuturesRestApi;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.FuturesAccountBalanceV2ResponseInner;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.FuturesAccountBalanceV3Response;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.Interval;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.KlineCandlestickDataResponse;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.KlineCandlestickDataResponseItem;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.api.DerivativesTradingUsdsFuturesWebSocketStreams;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.KlineCandlestickStreamsRequest;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.KlineCandlestickStreamsResponse;
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

@Service
@Slf4j
@RequiredArgsConstructor
public class BinanceService {


    private final DerivativesTradingUsdsFuturesRestApi restApi;
    private final DerivativesTradingUsdsFuturesWebSocketStreams webSocketStreams;
    private final BinanceProperties binanceProperties;

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

    public List<FuturesAccountBalanceV2ResponseInner> getAccountBalance() {
        ApiResponse<FuturesAccountBalanceV3Response> response = restApi.futuresAccountBalanceV3(5000L);
        List<FuturesAccountBalanceV2ResponseInner> balances = response.getData();
        dataPool.setBalances(balances); // 更新数据池
        return balances;
    }

    public List<KlineData> getKlineData(String symbol, String intervalStr) {
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
}
