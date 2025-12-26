package com.galaxy.auratrader.tool;

import com.galaxy.auratrader.llm.annotation.AIParam;
import com.galaxy.auratrader.llm.annotation.AITool;
import com.galaxy.auratrader.message.DingTalkMessageSender;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContextTool {
    private final DingTalkMessageSender dingTalkMessageSender;

    private String preContext = "";

    @AITool(name = "setPreContext", description = "设置要传递给下一位的信息", category = "context", timeout = 1000)
    public String setPreContext(
            @AIParam(name = "context", description = "要传递的信息")
            String context
    ) {
        this.preContext = context;
        try {

            dingTalkMessageSender.sendText(this.preContext);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return "Pre-context set.";
    }

    @AITool(name = "getPreContext", description = "获取上一位传递过来的信息", category = "context", timeout = 1000)
    public String getPreContext() {
        return this.preContext;
    }

}
