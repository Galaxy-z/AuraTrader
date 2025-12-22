package com.galaxy.auratrader;

import com.binance.connector.client.derivatives_trading_usds_futures.rest.api.DerivativesTradingUsdsFuturesRestApi;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.api.DerivativesTradingUsdsFuturesWebSocketStreams;
import com.galaxy.auratrader.llm.chat.Chatter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AuraTraderApplicationTests {

    @Autowired
    private DerivativesTradingUsdsFuturesRestApi derivativesTradingUsdsFuturesRestApi;
    @Autowired
    private DerivativesTradingUsdsFuturesWebSocketStreams derivativesTradingUsdsFuturesWebSocketStreams;

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
        chatter.chat();
    }

}
