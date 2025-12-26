package com.galaxy.auratrader.llm.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.galaxy.auratrader.llm.annotation.AIParam;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * 工具执行器（完整实现）
 */
@Slf4j
public class ToolExecutor {

    private final Object targetBean;
    private final Method method;
    private final Parameter[] parameters;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;

    public ToolExecutor(Object targetBean, Method method) {
        this.targetBean = targetBean;
        this.method = method;
        this.parameters = method.getParameters();
        this.objectMapper = new ObjectMapper();
        this.executorService = Executors.newSingleThreadExecutor();

        // 确保方法可访问
        method.setAccessible(true);
    }

    /**
     * 执行工具调用
     */
    public String execute(Map<String, Object> arguments) {
        return execute(arguments, 5000); // 默认5秒超时
    }

    /**
     * 执行工具调用（带超时）
     */
    public String execute(Map<String, Object> arguments, long timeoutMillis) {
        try {
            // 1. 验证参数
            validateArguments(arguments);

            // 2. 映射参数
            Object[] args = mapArguments(arguments);

            // 3. 异步执行（带超时）
            Future<Object> future = executorService.submit(() -> method.invoke(targetBean, args));

            try {
                // 4. 获取结果
                Object result = future.get(timeoutMillis, TimeUnit.MILLISECONDS);

                // 5. 处理结果
                return processResult(result);

            } catch (TimeoutException e) {
                future.cancel(true);
                throw new RuntimeException("工具执行超时: " + method.getName(), e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof InvocationTargetException) {
                    cause = ((InvocationTargetException) cause).getTargetException();
                }
                throw new RuntimeException("工具执行失败: " + method.getName(), cause);
            }

        } catch (Exception e) {
            log.error("执行工具失败: {}", method.getName(), e);
            throw new RuntimeException("执行工具失败: " + method.getName(), e);
        }
    }

    /**
     * 验证参数
     */
    private void validateArguments(Map<String, Object> arguments) {
        for (Parameter param : parameters) {
            String paramName = getParameterName(param);
            boolean isRequired = isRequired(param);

            if (isRequired && !arguments.containsKey(paramName)) {
                throw new IllegalArgumentException("缺少必需参数: " + paramName);
            }

            // 验证参数类型
            if (arguments.containsKey(paramName)) {
                Object value = arguments.get(paramName);
                validateParameterType(param, paramName, value);
            }
        }
    }

    /**
     * 获取参数名称
     */
    private String getParameterName(Parameter param) {
        // 优先使用@AIParam注解的name
        if (param.isAnnotationPresent(AIParam.class)) {
            String name = param.getAnnotation(AIParam.class).name();
            if (name != null && !name.trim().isEmpty()) {
                return name.trim();
            }
        }

        // 使用反射获取的参数名
        return param.getName();
    }

    /**
     * 检查参数是否必需
     */
    private boolean isRequired(Parameter param) {
        // 如果参数有@AIParam注解，使用注解的required值
        if (param.isAnnotationPresent(AIParam.class)) {
            return param.getAnnotation(AIParam.class).required();
        }

        // 基本类型和原始类型默认为必需
        Class<?> type = param.getType();
        if (type.isPrimitive()) {
            return true;
        }

        // 对于引用类型，检查是否为Optional
        if (Optional.class.isAssignableFrom(type)) {
            return false;
        }

        // 其他引用类型默认为必需
        return true;
    }

    /**
     * 验证参数类型
     */
    private void validateParameterType(Parameter param, String paramName, Object value) {
        if (value == null) {
            return; // null值跳过类型验证
        }

        Class<?> expectedType = param.getType();

        // 处理Optional类型
        boolean isOptional = false;
        if (Optional.class.isAssignableFrom(expectedType)) {
            isOptional = true;
            // 获取Optional的实际类型
            expectedType = getOptionalGenericType(param);
        }

        // 检查类型是否匹配
        if (isTypeCompatible(value, expectedType)) {
            return;
        }

        // 如果类型不匹配，尝试进行宽松的类型转换（例如 String "true" -> Boolean.TRUE，或数字字符串 -> 数字）
        try {
            Object converted = convertValueToType(value, expectedType);
            if (converted != null) {
                // 如果转换后类型匹配，则接受（映射阶段会再次进行实际转换）
                if (isTypeCompatible(converted, expectedType) || expectedType.isInstance(converted)) {
                    return;
                }
            } else {
                // converted == null: 对于基本类型这是不接受的
                if (!expectedType.isPrimitive()) {
                    return; // for reference types null is acceptable
                }
            }
        } catch (Exception ex) {
            // ignore conversion errors and throw below
            log.debug("参数 {} 的宽松转换失败，准备抛出类型不匹配异常", paramName);
        }

        throw new IllegalArgumentException(String.format(
                "参数类型不匹配: 参数 '%s' 期望类型 %s，实际类型 %s",
                paramName, expectedType.getSimpleName(), value.getClass().getSimpleName()
        ));
    }

    /**
     * 获取Optional的泛型类型
     */
    private Class<?> getOptionalGenericType(Parameter param) {
        // 简单的泛型类型解析（对于复杂场景可能需要使用Type工具）
        String typeName = param.getParameterizedType().getTypeName();

        if (typeName.startsWith("java.util.Optional<")) {
            String genericTypeName = typeName.substring("java.util.Optional<".length(), typeName.length() - 1);
            try {
                // 尝试加载类
                return Class.forName(genericTypeName);
            } catch (ClassNotFoundException e) {
                // 如果无法加载，默认使用Object
                return Object.class;
            }
        }

        return Object.class;
    }

    /**
     * 检查类型兼容性
     */
    private boolean isTypeCompatible(Object value, Class<?> expectedType) {
        if (expectedType == Object.class) {
            return true;
        }

        // 基本类型检查
        if (expectedType.isPrimitive()) {
            return isPrimitiveCompatible(value, expectedType);
        }

        // 包装类型检查
        if (expectedType == Integer.class && value instanceof Number) {
            return true;
        }
        if (expectedType == Long.class && value instanceof Number) {
            return true;
        }
        if (expectedType == Double.class && value instanceof Number) {
            return true;
        }
        if (expectedType == Float.class && value instanceof Number) {
            return true;
        }
        if (expectedType == Boolean.class && value instanceof Boolean) {
            return true;
        }
        if (expectedType == String.class && value instanceof String) {
            return true;
        }

        // 集合类型检查
        if (List.class.isAssignableFrom(expectedType) && value instanceof List) {
            return true;
        }
        if (Map.class.isAssignableFrom(expectedType) && value instanceof Map) {
            return true;
        }

        // 直接类检查
        return expectedType.isInstance(value);
    }

    /**
     * 基本类型兼容性检查
     */
    private boolean isPrimitiveCompatible(Object value, Class<?> primitiveType) {
        if (!(value instanceof Number)) {
            return false;
        }

        if (primitiveType == int.class || primitiveType == long.class ||
                primitiveType == float.class || primitiveType == double.class) {
            return true;
        }

        if (primitiveType == boolean.class && value instanceof Boolean) {
            return true;
        }

        return false;
    }

    /**
     * 映射参数
     */
    private Object[] mapArguments(Map<String, Object> arguments) {
        Object[] args = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            String paramName = getParameterName(param);

            // 获取参数值
            Object rawValue = arguments.get(paramName);

            // 转换参数值
            args[i] = convertValue(rawValue, param);
        }

        return args;
    }

    /**
     * 转换参数值
     */
    private Object convertValue(Object rawValue, Parameter param) {
        // 如果值为null，根据参数类型返回适当的null值
        if (rawValue == null) {
            return getNullValueForType(param);
        }

        Class<?> targetType = param.getType();

        // 处理Optional类型
        if (Optional.class.isAssignableFrom(targetType)) {
            if (rawValue == null) {
                return Optional.empty();
            }
            // 获取Optional的实际类型并转换
            Class<?> actualType = getOptionalGenericType(param);
            Object converted = convertValueToType(rawValue, actualType);
            return Optional.ofNullable(converted);
        }

        // 直接类型转换
        return convertValueToType(rawValue, targetType);
    }

    /**
     * 将值转换为特定类型
     */
    private Object convertValueToType(Object rawValue, Class<?> targetType) {
        try {
            // 如果已经是目标类型，直接返回
            if (targetType.isInstance(rawValue)) {
                return rawValue;
            }

            // 字符串转换
            if (targetType == String.class) {
                return rawValue.toString();
            }

            // 数字类型转换
            if (targetType == Integer.class || targetType == int.class) {
                if (rawValue instanceof Number) {
                    return ((Number) rawValue).intValue();
                } else if (rawValue instanceof String) {
                    return Integer.parseInt((String) rawValue);
                }
            }

            if (targetType == Long.class || targetType == long.class) {
                if (rawValue instanceof Number) {
                    return ((Number) rawValue).longValue();
                } else if (rawValue instanceof String) {
                    return Long.parseLong((String) rawValue);
                }
            }

            if (targetType == Double.class || targetType == double.class) {
                if (rawValue instanceof Number) {
                    return ((Number) rawValue).doubleValue();
                } else if (rawValue instanceof String) {
                    return Double.parseDouble((String) rawValue);
                }
            }

            if (targetType == Float.class || targetType == float.class) {
                if (rawValue instanceof Number) {
                    return ((Number) rawValue).floatValue();
                } else if (rawValue instanceof String) {
                    return Float.parseFloat((String) rawValue);
                }
            }

            // 布尔类型转换
            if (targetType == Boolean.class || targetType == boolean.class) {
                if (rawValue instanceof Boolean) {
                    return rawValue;
                } else if (rawValue instanceof String) {
                    return Boolean.parseBoolean((String) rawValue);
                } else if (rawValue instanceof Number) {
                    return ((Number) rawValue).intValue() != 0;
                }
            }

            // 使用Jackson进行复杂对象转换
            if (targetType == Map.class || targetType == List.class ||
                    targetType == Object.class || !targetType.isPrimitive()) {

                if (rawValue instanceof String) {
                    // 尝试解析JSON字符串
                    return objectMapper.readValue((String) rawValue, targetType);
                } else {
                    // 使用Jackson进行对象转换
                    JsonNode jsonNode = objectMapper.valueToTree(rawValue);
                    return objectMapper.treeToValue(jsonNode, targetType);
                }
            }

        } catch (Exception e) {
            log.warn("参数类型转换失败: {} -> {}",
                    rawValue.getClass().getSimpleName(),
                    targetType.getSimpleName(), e);
        }

        // 如果无法转换，尝试直接转换或抛出异常
        try {
            return targetType.cast(rawValue);
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(String.format(
                    "无法将类型 %s 转换为 %s",
                    rawValue.getClass().getSimpleName(),
                    targetType.getSimpleName()
            ));
        }
    }

    /**
     * 获取类型的null值
     */
    private Object getNullValueForType(Parameter param) {
        Class<?> type = param.getType();

        // 基本类型不能为null，返回默认值
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;

        // Optional类型返回Optional.empty()
        if (Optional.class.isAssignableFrom(type)) {
            return Optional.empty();
        }

        // 其他引用类型返回null
        return null;
    }

    /**
     * 处理结果
     */
    private String processResult(Object result) {
        if (result == null) {
            return "";
        }

        try {
            // 如果是字符串，直接返回
            if (result instanceof String) {
                return (String) result;
            }

            // 如果是简单类型，转换为字符串
            if (result instanceof Number || result instanceof Boolean) {
                return result.toString();
            }

            // 复杂对象转换为JSON
            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("结果处理失败", e);
            // 如果JSON转换失败，使用toString()
            return result.toString();
        }
    }

    /**
     * 获取方法信息
     */
    public Method getMethod() {
        return method;
    }

    /**
     * 获取目标Bean
     */
    public Object getTargetBean() {
        return targetBean;
    }

    /**
     * 清理资源
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

