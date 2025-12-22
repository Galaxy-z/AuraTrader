package com.galaxy.auratrader.llm.tool;

import lombok.Builder;
import lombok.Data;

/**
 * 工具执行统计信息
 */
@Data
@Builder
public class ToolStatistics {

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 总调用次数
     */
    @Builder.Default
    private long totalCalls = 0;

    /**
     * 成功次数
     */
    @Builder.Default
    private long successCalls = 0;

    /**
     * 失败次数
     */
    @Builder.Default
    private long failedCalls = 0;

    /**
     * 平均执行时间（毫秒）
     */
    @Builder.Default
    private double averageExecutionTime = 0;

    /**
     * 最后调用时间
     */
    private long lastCallTime;

    /**
     * 最后调用结果
     */
    private String lastCallResult;
}
