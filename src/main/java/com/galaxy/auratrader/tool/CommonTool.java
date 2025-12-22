package com.galaxy.auratrader.tool;

import com.galaxy.auratrader.llm.annotation.AIParam;
import com.galaxy.auratrader.llm.annotation.AITool;
import org.springframework.stereotype.Component;

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
        return 25+"°C";
    }
}
