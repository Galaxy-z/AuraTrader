package com.galaxy.auratrader.llm.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 参数元数据
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ParamMetadata {

    /**
     * 参数名称
     */
    private String name;

    /**
     * 参数描述
     */
    private String description;

    /**
     * 参数类型（string, number, integer, boolean, array, object）
     */
    private String type;

    /**
     * 是否必需
     */
    @Builder.Default
    private boolean required = true;

    /**
     * 默认值（JSON字符串格式）
     */
    private String defaultValue;

    /**
     * 枚举值列表
     */
    private List<String> enumValues;

    /**
     * 最小值（针对数字类型）
     */
    private Number minimum;

    /**
     * 最大值（针对数字类型）
     */
    private Number maximum;

    /**
     * 最小长度（针对字符串/数组类型）
     */
    private Integer minLength;

    /**
     * 最大长度（针对字符串/数组类型）
     */
    private Integer maxLength;

    /**
     * 正则表达式模式（针对字符串类型）
     */
    private String pattern;

    /**
     * 数组项类型（针对array类型）
     */
    private String itemsType;

    /**
     * 对象属性定义（针对object类型）
     */
    private Map<String, ParamMetadata> properties;
}
