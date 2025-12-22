package com.galaxy.auratrader.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.galaxy.auratrader.llm.annotation.AITool;
import com.galaxy.auratrader.model.DataPool;
import com.galaxy.auratrader.model.KlineData;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DataTool {

    private DataPool dataPool = DataPool.getInstance();

    private ObjectMapper objectMapper = new ObjectMapper();

    @AITool(name = "getCurrentPairsAndTimeFrame", description = "获取当前交易对和时间周期", category = "data", timeout = 1000)
    public String getCurrentPairsAndTimeFrame(){
        String currentPair = dataPool.getCurrentPair();
        String currentTimeFrame = dataPool.getCurrentInterval();
        return "当前交易对: " + currentPair + ", 当前时间周期: " + currentTimeFrame;
    }

    @AITool(name = "getKLineData", description = "获取当前交易对的K线数据", category = "data", timeout = 2000)
    public String getKLineData(){
        List<KlineData> klineData = dataPool.getKlineData();
        try {
            return objectMapper.writeValueAsString(klineData);
        } catch (Exception e) {
            return "Error converting Kline data to JSON: " + e.getMessage();
        }
    }

    @AITool(name = "getIndicatorData", description = "获取当前交易对的技术指标数据", category = "data", timeout = 2000)
    public String getIndicatorData(){
        try {
            return objectMapper.writeValueAsString(dataPool.getIndicators());
        } catch (Exception e) {
            return "Error converting Indicator data to JSON: " + e.getMessage();
        }
    }


}
