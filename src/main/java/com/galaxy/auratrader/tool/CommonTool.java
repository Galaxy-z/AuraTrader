package com.galaxy.auratrader.tool;

import com.galaxy.auratrader.llm.annotation.AIParam;
import com.galaxy.auratrader.llm.annotation.AITool;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
public class CommonTool {


    @AITool(name = "getCityTemperature", description = "获取城市的气温", category = "utility", timeout = 2000)
    public String getCityTemperature(
            @AIParam(
                    name = "cityName",
                    description = "城市名称",
                    required = true,
                    type = "string"
            )
            String cityName
    ) {
        return 2+"°C";
    }

    @AITool(name = "getCityHumidity", description = "获取城市的湿度", category = "utility", timeout = 2000)
    public String getCityHumidity(
            @AIParam(
                    name = "cityName",
                    description = "城市名称",
                    required = true,
                    type = "string"
            )
            String cityName) {

        return 60+"%";
    }

    @AITool(name = "getCurrentTime", description = "获取当前时区时间，格式yyyy-MM-dd HH:mm:ss", category = "utility", timeout = 1000)
    public String getCurrentTime() {
        // Return current time in system default timezone formatted as yyyy-MM-dd HH:mm:ss
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return ZonedDateTime.now(ZoneId.systemDefault()).format(formatter);
    }

}
