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
}

