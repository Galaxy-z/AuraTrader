package com.galaxy.auratrader.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "binance")
@Data
public class BinanceProperties {
    private List<String> pairs;
    // Global recvWindow (milliseconds) to use for Binance REST calls. Default to 500ms.
    private Long recvWindow = 500L;
}
