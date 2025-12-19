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

        //[
        // {
        //   "accountAlias": "SgsR",              // 账户唯一识别码
        //   "asset": "USDT",  	                // 资产
        //   "balance": "122607.35137903",        // 总余额
        //   "crossWalletBalance": "23.72469206", // 全仓余额
        //   "crossUnPnl": "0.00000000",           // 全仓持仓未实现盈亏
        //   "availableBalance": "23.72469206",   // 下单可用余额
        //   "maxWithdrawAmount": "23.72469206",  // 最大可转出余额
        //   "marginAvailable": true,            // 是否可用作联合保证金
        //   "updateTime": 1617939110373
        // }
        //]

        ApiResponse<FuturesAccountBalanceV3Response> response = derivativesTradingUsdsFuturesRestApi.futuresAccountBalanceV3(5000L);
        for (FuturesAccountBalanceV2ResponseInner datum : response.getData()) {

        }



    }

    @Test
    void chat(){
        chatter.chat();
    }

}
