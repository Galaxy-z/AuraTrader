package com.galaxy.auratrader.llm.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 工具元数据 - 存储工具定义信息
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolMetadata {

    /**
     * 工具名称（唯一标识）
     */
    private String name;

    /**
     * 工具描述
     */
    private String description;

    /**
     * 工具分类
     */
    private String category;

    /**
     * 是否启用
     */
    @Builder.Default
    private boolean enabled = true;

    /**
     * 执行超时时间（毫秒）
     */
    @Builder.Default
    private long timeout = 5000;

    /**
     * 是否需要用户确认
     */
    @Builder.Default
    private boolean requireConfirmation = false;

    /**
     * 参数列表
     */
    private List<ParamMetadata> parameters;

    /**
     * 返回结果描述
     */
    private String resultDescription;

    /**
     * 返回结果示例
     */
    private String resultExample;

    /**
     * 附加元数据
     */
    private Map<String, Object> extraMetadata;

    /**
     * 创建时间
     */
    @Builder.Default
    private long createdAt = System.currentTimeMillis();

    /**
     * 最后修改时间
     */
    @Builder.Default
    private long updatedAt = System.currentTimeMillis();
}
