package com.galaxy.auratrader;

import com.binance.connector.client.common.ApiResponse;
import com.binance.connector.client.common.websocket.service.StreamBlockingQueueWrapper;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.api.DerivativesTradingUsdsFuturesRestApi;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.*;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.api.model.AccountInformationRequest;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.api.DerivativesTradingUsdsFuturesWebSocketStreams;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.ContinuousContractKlineCandlestickStreamsRequest;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.ContinuousContractKlineCandlestickStreamsResponse;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.KlineCandlestickStreamsRequest;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.model.KlineCandlestickStreamsResponse;
import com.galaxy.auratrader.llm.chat.Chatter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Date;

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
        chatter.functionTest();

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
