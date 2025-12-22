package com.galaxy.auratrader.llm.tool;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 工具调用上下文
 */
@Data
@Builder
public class ToolCallContext {

    /**
     * 工具调用ID
     */
    private String callId;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 调用时间
     */
    @Builder.Default
    private long callTime = System.currentTimeMillis();

    /**
     * 调用参数
     */
    private Map<String, Object> parameters;

    /**
     * 原始调用参数（JSON字符串）
     */
    private String rawParameters;

    /**
     * 调用结果
     */
    private String result;

    /**
     * 执行状态
     */
    private CallStatus status;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 执行耗时（毫秒）
     */
    private long executionTime;

    /**
     * 调用链跟踪ID
     */
    private String traceId;

    public enum CallStatus {
        PENDING,    // 等待执行
        EXECUTING,  // 执行中
        SUCCESS,    // 执行成功
        FAILED,     // 执行失败
        TIMEOUT     // 执行超时
    }

    public void initParameters() {
        if (StrUtil.isEmpty(rawParameters)){
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        try {
            this.parameters = mapper.readValue(rawParameters, new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse rawParameters: " + rawParameters, e);
        }
    }
}
