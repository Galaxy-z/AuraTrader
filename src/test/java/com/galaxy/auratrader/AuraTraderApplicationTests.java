package com.galaxy.auratrader;

import cn.hutool.json.JSONUtil;
import com.binance.connector.client.common.ApiResponse;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.api.DerivativesTradingUsdsFuturesRestApi;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.CheckServerTimeResponse;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.api.DerivativesTradingUsdsFuturesWebSocketStreams;
import com.galaxy.auratrader.llm.chat.Chatter;
import com.galaxy.auratrader.service.BinanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AuraTraderApplicationTests {

    @Autowired
    private DerivativesTradingUsdsFuturesRestApi restApi;
    @Autowired
    private DerivativesTradingUsdsFuturesWebSocketStreams derivativesTradingUsdsFuturesWebSocketStreams;
    @Autowired
    private BinanceService binanceService;

    @Autowired
    private Chatter chatter;

    @Test
    void contextLoads() throws InterruptedException {
        chatter.toolCall("南京现在的时间和温度是多少？", false);

        try {
            Thread.sleep(50000);
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    @Test
    void chat(){

        ApiResponse<CheckServerTimeResponse> response = restApi.checkServerTime();
        System.out.println("Server time: " + response.getData().getServerTime());
        response.getRateLimits().forEach(
                (type, limit) ->
                        System.out.println("Rate limit - " + type + ": " + JSONUtil.toJsonPrettyStr(limit))
        );
    }

}
