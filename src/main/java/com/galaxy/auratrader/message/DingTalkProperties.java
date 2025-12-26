package com.galaxy.auratrader.message;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "dingtalk")
public class DingTalkProperties {
    private String accessToken;
    private String secret;
    private List<String> defaultAtMobiles = new ArrayList<>();
    private List<String> defaultAtUserIds = new ArrayList<>();
    private boolean defaultAtAll;
}

