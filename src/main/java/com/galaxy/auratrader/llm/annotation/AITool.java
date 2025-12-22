package com.galaxy.auratrader.llm.annotation;

import java.lang.annotation.*;

/**
 * AI工具注解 - 标记可被AI调用的方法
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface AITool {
    /**
     * 工具名称（AI识别的名称）
     */
    String name();

    /**
     * 工具描述（AI决策的依据）
     */
    String description();

    /**
     * 工具分类（用于分组管理）
     */
    String category() default "general";

    /**
     * 是否需要用户确认（敏感操作）
     */
    boolean requireConfirmation() default false;

    /**
     * 执行超时时间（毫秒）
     */
    long timeout() default 5000;
}
