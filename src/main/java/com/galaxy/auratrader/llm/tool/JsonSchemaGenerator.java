package com.galaxy.auratrader.llm.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * JSON Schema 生成器（用于OpenAI函数调用参数定义）
 */
@Component
public class JsonSchemaGenerator {

    private final ObjectMapper objectMapper;

    public JsonSchemaGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 根据工具元数据生成OpenAI函数定义
     */
    public ObjectNode generateFunctionDefinition(ToolMetadata metadata) {
        ObjectNode functionNode = objectMapper.createObjectNode();

        // 基础信息
        functionNode.put("name", metadata.getName());
        functionNode.put("description", metadata.getDescription());

        // 参数schema
        ObjectNode parameters = objectMapper.createObjectNode();
        parameters.put("type", "object");

        // 属性定义
        ObjectNode properties = objectMapper.createObjectNode();
        List<ParamMetadata> paramList = metadata.getParameters();

        if (paramList != null && !paramList.isEmpty()) {
            for (ParamMetadata param : paramList) {
                ObjectNode paramSchema = generateParamSchema(param);
                properties.set(param.getName(), paramSchema);
            }
        }

        parameters.set("properties", properties);

        // 必需参数列表
        ArrayNode requiredArray = objectMapper.createArrayNode();
        if (paramList != null) {
            paramList.stream()
                    .filter(ParamMetadata::isRequired)
                    .forEach(param -> requiredArray.add(param.getName()));
        }

        if (requiredArray.size() > 0) {
            parameters.set("required", requiredArray);
        }

        // 添加附加约束
        parameters.put("additionalProperties", false);

        functionNode.set("parameters", parameters);
        return functionNode;
    }

    /**
     * 生成参数schema
     */
    private ObjectNode generateParamSchema(ParamMetadata param) {
        ObjectNode paramNode = objectMapper.createObjectNode();

        // 基本类型
        paramNode.put("type", param.getType());

        // 描述
        if (param.getDescription() != null && !param.getDescription().isEmpty()) {
            paramNode.put("description", param.getDescription());
        }

        // 默认值
        if (param.getDefaultValue() != null) {
            try {
                Object defaultValue = objectMapper.readValue(param.getDefaultValue(), Object.class);
                paramNode.set("default", objectMapper.valueToTree(defaultValue));
            } catch (Exception e) {
                // 忽略默认值解析错误
            }
        }

        // 枚举值
        if (param.getEnumValues() != null && !param.getEnumValues().isEmpty()) {
            ArrayNode enumArray = objectMapper.createArrayNode();
            param.getEnumValues().forEach(enumArray::add);
            paramNode.set("enum", enumArray);
        }

        // 数值范围约束
        if (param.getMinimum() != null) {
            paramNode.put("minimum", param.getMinimum().doubleValue());
        }

        if (param.getMaximum() != null) {
            paramNode.put("maximum", param.getMaximum().doubleValue());
        }

        // 字符串/数组长度约束
        if (param.getMinLength() != null) {
            paramNode.put("minLength", param.getMinLength());
        }

        if (param.getMaxLength() != null) {
            paramNode.put("maxLength", param.getMaxLength());
        }

        // 正则表达式模式
        if (param.getPattern() != null && !param.getPattern().isEmpty()) {
            paramNode.put("pattern", param.getPattern());
        }

        // 数组项类型
        if ("array".equals(param.getType()) && param.getItemsType() != null) {
            ObjectNode itemsNode = objectMapper.createObjectNode();
            itemsNode.put("type", param.getItemsType());
            paramNode.set("items", itemsNode);
        }

        // 对象属性定义
        if ("object".equals(param.getType()) && param.getProperties() != null) {
            ObjectNode propertiesNode = objectMapper.createObjectNode();
            Map<String, ParamMetadata> properties = param.getProperties();

            for (Map.Entry<String, ParamMetadata> entry : properties.entrySet()) {
                ObjectNode propSchema = generateParamSchema(entry.getValue());
                propertiesNode.set(entry.getKey(), propSchema);
            }

            paramNode.set("properties", propertiesNode);
        }

        return paramNode;
    }

    /**
     * 生成完整的OpenAI工具定义
     */
    public ObjectNode generateToolDefinition(ToolMetadata metadata) {
        ObjectNode toolNode = objectMapper.createObjectNode();
        toolNode.put("type", "function");
        toolNode.set("function", generateFunctionDefinition(metadata));
        return toolNode;
    }
}
