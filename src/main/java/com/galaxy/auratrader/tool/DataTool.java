package com.galaxy.auratrader.tool;

import cn.hutool.core.util.StrUtil;
import com.binance.connector.client.common.ApiResponse;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.api.DerivativesTradingUsdsFuturesRestApi;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.*;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.api.api.DerivativesTradingUsdsFuturesWebSocketApi;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.api.DerivativesTradingUsdsFuturesWebSocketStreams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.galaxy.auratrader.llm.annotation.AIParam;
import com.galaxy.auratrader.llm.annotation.AITool;
import com.galaxy.auratrader.model.DataPool;
import com.galaxy.auratrader.model.KlineData;
import com.galaxy.auratrader.service.IndicatorService;
import com.galaxy.auratrader.util.CommonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@Component
@RequiredArgsConstructor
public class DataTool {

    private DataPool dataPool = DataPool.getInstance();

    private ObjectMapper objectMapper = new ObjectMapper();

    private final DerivativesTradingUsdsFuturesRestApi restApi;
    private final DerivativesTradingUsdsFuturesWebSocketStreams webSocketStreams;
    private final DerivativesTradingUsdsFuturesWebSocketApi webSocketApi;

    private final IndicatorService indicatorService;

    @AITool(
            name = "getCurrentPairsAndTimeFrame",
            description = "获取当前交易对和时间周期",
            category = "data",
            timeout = 1000
    )
    public String getCurrentPairsAndTimeFrame() {
        String currentPair = dataPool.getCurrentPair();
        String currentTimeFrame = dataPool.getCurrentInterval();
        return "当前交易对: " + currentPair + ", 当前时间周期: " + currentTimeFrame;
    }

    @AITool(
            name = "getKLineData",
            description = "获取交易对的K线数据以及技术指标数据",
            category = "data",
            timeout = 2000
    )
    public String getKLineData(
            @AIParam(name = "pair", description = "交易对,如'ETHUSDT'") String pair,
            @AIParam(name = "interval", description = "数据的时间框架", type = "enum", enumValues = {"1m", "5m", "15m", "1h", "4h", "1d"}) String interval,
            @AIParam(name = "starTime", description = "请求数据的起始时间,不填默认返回最新数据，yyyy-MM-dd HH:mm:ss格式",required = false) String startTime,
            @AIParam(name = "endTime", description = "请求数据的结束时间，不填默认返回最新数据，yyyy-MM-dd HH:mm:ss格式",required = false) String endTime,
            @AIParam(name = "limit", description = "请求的数据数量，默认值:500 最大值:500", required = false, type = "number") Long limit
    ) {
        Map<String, Object> result = new HashMap<>();
        ApiResponse<ContinuousContractKlineCandlestickDataResponse> response = restApi.continuousContractKlineCandlestickData(
                pair,
                ContractType.PERPETUAL,
                Interval.fromValue(interval),
                CommonUtil.dateToUnixTimestampMillis(startTime),
                CommonUtil.dateToUnixTimestampMillis(endTime),
                limit == null || limit <= 0 ? null : limit
        );
        if (response.getStatusCode() != 200) {
            return "Error fetching Kline data: " + response;
        }
        List<KlineData> klineData = response.getData().stream()
                .map(this::parseKlineData).toList();
        IndicatorService.IndicatorResult indicatorResult = indicatorService.computeIndicators(klineData, 20, 14);
        result.put("klineData", klineData);
        result.put("indicators", indicatorResult);
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "Error converting Kline data to JSON: " + e.getMessage();
        }
    }

    private KlineData parseKlineData(ContinuousContractKlineCandlestickDataResponseItem item) {
        Date openTime = new Date(Long.parseLong(item.get(0)));
        BigDecimal open = new BigDecimal(item.get(1));
        BigDecimal high = new BigDecimal(item.get(2));
        BigDecimal low = new BigDecimal(item.get(3));
        BigDecimal close = new BigDecimal(item.get(4));
        BigDecimal volume = new BigDecimal(item.get(5));
        Date closeTime = new Date(Long.parseLong(item.get(6)));

        return new KlineData(openTime, open, high, low, close, volume, closeTime);
    }

//    @AITool(name = "getIndicatorData", description = "获取当前交易对的技术指标数据", category = "data", timeout = 2000)
//    public String getIndicatorData() {
//        try {
//            return objectMapper.writeValueAsString(dataPool.getIndicators());
//        } catch (Exception e) {
//            return "Error converting Indicator data to JSON: " + e.getMessage();
//        }
//    }


}
