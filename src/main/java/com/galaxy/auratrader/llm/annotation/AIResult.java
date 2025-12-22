package com.galaxy.auratrader.llm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AIResult {
    /**
     * 结果描述
     */
    String description() default "";

    /**
     * 结果示例
     */
    String example() default "";
}
