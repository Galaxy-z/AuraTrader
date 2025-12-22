package com.galaxy.auratrader.config;

import com.binance.connector.client.common.configuration.ClientConfiguration;
import com.binance.connector.client.common.configuration.SignatureConfiguration;
import com.binance.connector.client.common.websocket.configuration.WebSocketClientConfiguration;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.DerivativesTradingUsdsFuturesRestApiUtil;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.api.DerivativesTradingUsdsFuturesRestApi;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.api.DerivativesTradingUsdsFuturesWebSocketApiUtil;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.api.api.DerivativesTradingUsdsFuturesWebSocketApi;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.DerivativesTradingUsdsFuturesWebSocketStreamsUtil;
import com.binance.connector.client.derivatives_trading_usds_futures.websocket.stream.api.DerivativesTradingUsdsFuturesWebSocketStreams;
import org.eclipse.jetty.client.HttpProxy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.net.Proxy;

@Configuration
public class BinanceApiConfiguration {

    @Value("${binance.api.key}")
    private String apiKey;

    @Value("${binance.api.secret}")
    private String secretKey;

    @Value("${binance.testnet:false}")
    private Boolean testnet;

    // Read proxy settings from environment / .env / application properties.
    // If not provided (empty host or port <= 0) we won't use a proxy.
    @Value("${proxy.host:}")
    private String proxyHost;

    @Value("${proxy.port:0}")
    private Integer proxyPort;

    @Bean
    public DerivativesTradingUsdsFuturesRestApi derivativesTradingUsdsFuturesRestApi() {
        ClientConfiguration clientConfiguration =
                DerivativesTradingUsdsFuturesRestApiUtil.getClientConfiguration();
        if(testnet) {
            clientConfiguration.setUrl("https://testnet.binancefuture.com");
        }
        // Only set HTTP proxy if host is provided and port is a positive integer
        if (proxyHost != null && !proxyHost.trim().isEmpty() && proxyPort != null && proxyPort > 0) {
            clientConfiguration.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost.trim(), proxyPort)));
        }
        SignatureConfiguration signatureConfiguration = new SignatureConfiguration();
        signatureConfiguration.setApiKey(apiKey);
        signatureConfiguration.setSecretKey(secretKey);
        clientConfiguration.setSignatureConfiguration(signatureConfiguration);
        return new DerivativesTradingUsdsFuturesRestApi(clientConfiguration);
    }

    @Bean
    public DerivativesTradingUsdsFuturesWebSocketApi derivativesTradingUsdsFuturesWebSocketApi() {
        WebSocketClientConfiguration clientConfiguration =
                DerivativesTradingUsdsFuturesWebSocketApiUtil.getClientConfiguration();
        // if you want the connection to be auto logged on:
        // https://developers.binance.com/docs/binance-spot-api-docs/websocket-api/authentication-requests
        clientConfiguration.setAutoLogon(true);
        // Only set WebSocket proxy if configured
        if (proxyHost != null && !proxyHost.trim().isEmpty() && proxyPort != null && proxyPort > 0) {
            clientConfiguration.setWebSocketProxy(new HttpProxy(proxyHost.trim(), proxyPort));
        }
        SignatureConfiguration signatureConfiguration = new SignatureConfiguration();
        signatureConfiguration.setApiKey(apiKey);
        signatureConfiguration.setSecretKey(secretKey);
        clientConfiguration.setSignatureConfiguration(signatureConfiguration);
        return new DerivativesTradingUsdsFuturesWebSocketApi(clientConfiguration);
    }

    @Bean
    public DerivativesTradingUsdsFuturesWebSocketStreams derivativesTradingUsdsFuturesWebSocketStreams() {
        WebSocketClientConfiguration clientConfiguration = DerivativesTradingUsdsFuturesWebSocketStreamsUtil.getClientConfiguration();
        if (testnet){
            clientConfiguration.setUrl("wss://fstream.binancefuture.com/market/stream");
        }
        // Only set WebSocket streams proxy if configured
        if (proxyHost != null && !proxyHost.trim().isEmpty() && proxyPort != null && proxyPort > 0) {
            clientConfiguration.setWebSocketProxy(new HttpProxy(proxyHost.trim(), proxyPort));
        }
        SignatureConfiguration signatureConfiguration = new SignatureConfiguration();
        signatureConfiguration.setApiKey(apiKey);
        signatureConfiguration.setSecretKey(secretKey);
        clientConfiguration.setSignatureConfiguration(signatureConfiguration);
        return new DerivativesTradingUsdsFuturesWebSocketStreams(clientConfiguration);
    }

}
