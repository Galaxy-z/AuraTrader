package com.galaxy.auratrader.llm.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.galaxy.auratrader.llm.annotation.AIParam;
import com.galaxy.auratrader.llm.annotation.AIResult;
import com.galaxy.auratrader.llm.annotation.AITool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * AI工具注册中心（完整版）
 */
@Component
@Slf4j
public class AIToolRegistry {
    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JsonSchemaGenerator schemaGenerator;

    private final Map<String, ToolExecutor> toolExecutors = new ConcurrentHashMap<>();
    private final Map<String, ToolMetadata> toolMetadata = new ConcurrentHashMap<>();
    private final Map<String, ToolStatistics> toolStatistics = new ConcurrentHashMap<>();

    /**
     * 初始化：扫描并注册所有@AITool注解的方法
     */
    @PostConstruct
    public void init() {
        log.info("开始扫描AI工具注解...");

        Map<String, Object> beans = applicationContext.getBeansOfType(Object.class);

        log.info("找到 {} 个Spring管理的Bean，开始扫描工具方法", beans.size());
        for (Object bean : beans.values()) {
            // 跳过Spring自身的bean
            String beanName = bean.getClass().getName();
            if (beanName.startsWith("org.springframework")) {
                continue;
            }

            scanBeanForTools(bean);
        }

        log.info("AI工具扫描完成，共注册 {} 个工具", toolExecutors.size());
        logRegisteredTools();
    }

    /**
     * 扫描单个Bean的方法
     */
    private void scanBeanForTools(Object bean) {
        Class<?> beanClass = bean.getClass();

        // 获取所有方法（包括父类的方法）
        Method[] methods = beanClass.getMethods();

        for (Method method : methods) {
            if (method.isAnnotationPresent(AITool.class)) {
                registerToolMethod(bean, method);
            }
        }
    }

    /**
     * 注册工具方法
     */
    private void registerToolMethod(Object bean, Method method) {
        try {
            AITool toolAnnotation = method.getAnnotation(AITool.class);
            String toolName = toolAnnotation.name();

            // 检查是否已注册
            if (toolExecutors.containsKey(toolName)) {
                log.warn("工具名称重复: {}，跳过注册", toolName);
                return;
            }

            // 创建工具元数据
            ToolMetadata metadata = createToolMetadata(method, toolAnnotation);

            // 创建执行器
            ToolExecutor executor = new ToolExecutor(bean, method);

            // 注册到管理器
            toolMetadata.put(toolName, metadata);
            toolExecutors.put(toolName, executor);

            // 初始化统计信息
            toolStatistics.put(toolName, ToolStatistics.builder()
                    .toolName(toolName)
                    .build());

            log.info("注册AI工具: {} -> {}.{}",
                    toolName,
                    bean.getClass().getSimpleName(),
                    method.getName());

        } catch (Exception e) {
            log.error("注册工具失败: {}.{}",
                    bean.getClass().getSimpleName(),
                    method.getName(), e);
        }
    }

    /**
     * 创建工具元数据
     */
    private ToolMetadata createToolMetadata(Method method, AITool toolAnnotation) {
        ToolMetadata.ToolMetadataBuilder builder = ToolMetadata.builder()
                .name(toolAnnotation.name())
                .description(toolAnnotation.description())
                .category(toolAnnotation.category())
                .timeout(toolAnnotation.timeout())
                .requireConfirmation(toolAnnotation.requireConfirmation());

        // 解析参数
        List<ParamMetadata> parameters = extractParameters(method);
        builder.parameters(parameters);

        // 解析返回值注解
        if (method.isAnnotationPresent(AIResult.class)) {
            AIResult resultAnnotation = method.getAnnotation(AIResult.class);
            builder.resultDescription(resultAnnotation.description());
            builder.resultExample(resultAnnotation.example());
        }

        return builder.build();
    }

    /**
     * 提取方法参数信息
     */
    private List<ParamMetadata> extractParameters(Method method) {
        List<ParamMetadata> paramList = new ArrayList<>();
        Parameter[] parameters = method.getParameters();

        for (Parameter param : parameters) {
            ParamMetadata.ParamMetadataBuilder paramBuilder = ParamMetadata.builder();

            // 获取参数名（使用编译后的参数名或注解）
            String paramName = getParameterName(param);
            paramBuilder.name(paramName);

            // 基本类型信息
            paramBuilder.type(mapJavaTypeToJsonType(param.getType()));

            // 处理@AIParam注解
            if (param.isAnnotationPresent(AIParam.class)) {
                AIParam aiParam = param.getAnnotation(AIParam.class);
                paramBuilder.description(aiParam.description());
                paramBuilder.required(aiParam.required());

                // 如果注解指定了类型，使用注解的类型
                if (!aiParam.type().isEmpty()) {
                    paramBuilder.type(aiParam.type());
                }

                // 枚举值
                if (aiParam.enumValues().length > 0) {
                    paramBuilder.enumValues(Arrays.asList(aiParam.enumValues()));
                }
            } else {
                // 没有注解时，设置默认描述
                paramBuilder.description("参数: " + paramName);
            }

            paramList.add(paramBuilder.build());
        }

        return paramList;
    }

    /**
     * 获取参数名（支持编译时参数名保留）
     */
    private String getParameterName(Parameter parameter) {
        // 优先使用@AIParam注解的name
        if (parameter.isAnnotationPresent(AIParam.class)) {
            String name = parameter.getAnnotation(AIParam.class).name();
            if (!name.isEmpty()) {
                return name;
            }
        }

        // 使用反射获取的参数名（需要编译时添加-parameters参数）
        return parameter.getName();
    }

    /**
     * Java类型映射到JSON Schema类型
     */
    private String mapJavaTypeToJsonType(Class<?> javaType) {
        if (String.class.isAssignableFrom(javaType)) {
            return "string";
        } else if (Number.class.isAssignableFrom(javaType) ||
                javaType == int.class || javaType == long.class ||
                javaType == float.class || javaType == double.class) {
            return "number";
        } else if (Boolean.class.isAssignableFrom(javaType) || javaType == boolean.class) {
            return "boolean";
        } else if (List.class.isAssignableFrom(javaType) ||
                Set.class.isAssignableFrom(javaType) ||
                javaType.isArray()) {
            return "array";
        } else if (Map.class.isAssignableFrom(javaType)) {
            return "object";
        } else if (Object.class.equals(javaType)) {
            return "object";
        } else {
            // 自定义对象
            return "object";
        }
    }

    /**
     * 获取所有工具定义（用于OpenAI API）
     */
    public List<ObjectNode> getAllToolDefinitions() {
        return toolMetadata.values().stream()
                .filter(metadata -> metadata.isEnabled())
                .map(schemaGenerator::generateToolDefinition)
                .collect(Collectors.toList());
    }

    /**
     * 执行工具调用（带监控和统计）
     */
    public String executeTool(ToolCallContext context) {
        String toolName = context.getToolName();

        // 更新上下文状态
        context.setStatus(ToolCallContext.CallStatus.EXECUTING);
        long startTime = System.currentTimeMillis();

        try {
            // 获取执行器
            ToolExecutor executor = toolExecutors.get(toolName);
            if (executor == null) {
                throw new IllegalArgumentException("工具不存在: " + toolName);
            }

            // 执行工具
            String result = executor.execute(context.getParameters());

            // 更新上下文
            long endTime = System.currentTimeMillis();
            context.setResult(result);
            context.setStatus(ToolCallContext.CallStatus.SUCCESS);
            context.setExecutionTime(endTime - startTime);

            // 更新统计信息
            updateStatistics(toolName, true, endTime - startTime, result);

            return result;

        } catch (Exception e) {
            // 更新上下文
            long endTime = System.currentTimeMillis();
            context.setStatus(ToolCallContext.CallStatus.FAILED);
            context.setErrorMessage(e.getMessage());
            context.setExecutionTime(endTime - startTime);

            // 更新统计信息
            updateStatistics(toolName, false, endTime - startTime, null);

            throw new RuntimeException("工具执行失败: " + toolName, e);
        }
    }

    /**
     * 更新统计信息
     */
    private synchronized void updateStatistics(String toolName, boolean success,
                                               long executionTime, String result) {
        ToolStatistics stats = toolStatistics.get(toolName);
        if (stats == null) {
            stats = ToolStatistics.builder().toolName(toolName).build();
            toolStatistics.put(toolName, stats);
        }

        stats.setTotalCalls(stats.getTotalCalls() + 1);

        if (success) {
            stats.setSuccessCalls(stats.getSuccessCalls() + 1);
        } else {
            stats.setFailedCalls(stats.getFailedCalls() + 1);
        }

        // 更新平均执行时间
        long totalTime = (long) (stats.getAverageExecutionTime() * (stats.getTotalCalls() - 1));
        stats.setAverageExecutionTime((totalTime + executionTime) / (double) stats.getTotalCalls());

        stats.setLastCallTime(System.currentTimeMillis());
        stats.setLastCallResult(result);
    }

    /**
     * 获取工具元数据
     */
    public ToolMetadata getToolMetadata(String toolName) {
        return toolMetadata.get(toolName);
    }

    /**
     * 获取所有工具元数据
     */
    public List<ToolMetadata> getAllToolMetadata() {
        return new ArrayList<>(toolMetadata.values());
    }

    /**
     * 获取工具统计信息
     */
    public Map<String, ToolStatistics> getToolStatistics() {
        return new HashMap<>(toolStatistics);
    }

    /**
     * 禁用工具
     */
    public boolean disableTool(String toolName) {
        ToolMetadata metadata = toolMetadata.get(toolName);
        if (metadata != null) {
            metadata.setEnabled(false);
            metadata.setUpdatedAt(System.currentTimeMillis());
            return true;
        }
        return false;
    }

    /**
     * 启用工具
     */
    public boolean enableTool(String toolName) {
        ToolMetadata metadata = toolMetadata.get(toolName);
        if (metadata != null) {
            metadata.setEnabled(true);
            metadata.setUpdatedAt(System.currentTimeMillis());
            return true;
        }
        return false;
    }

    /**
     * 记录已注册的工具
     */
    private void logRegisteredTools() {
        if (log.isInfoEnabled()) {
            StringBuilder logMsg = new StringBuilder("\n========== 已注册的AI工具 ==========\n");

            toolMetadata.values().stream()
                    .sorted(Comparator.comparing(ToolMetadata::getCategory)
                            .thenComparing(ToolMetadata::getName))
                    .forEach(metadata -> {
                        logMsg.append(String.format("🔧 [%s] %s\n",
                                metadata.getCategory(), metadata.getName()));
                        logMsg.append(String.format("   📝 %s\n", metadata.getDescription()));

                        if (metadata.getParameters() != null && !metadata.getParameters().isEmpty()) {
                            logMsg.append("   📊 参数:\n");
                            metadata.getParameters().forEach(param -> {
                                logMsg.append(String.format("     - %s (%s): %s%s\n",
                                        param.getName(),
                                        param.getType(),
                                        param.getDescription(),
                                        param.isRequired() ? " [必需]" : ""));
                            });
                        }

                        logMsg.append("\n");
                    });

            logMsg.append("===================================");
            log.info(logMsg.toString());
        }
    }
}
