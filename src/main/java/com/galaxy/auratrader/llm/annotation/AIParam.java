package com.galaxy.auratrader.llm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
/**
 * 参数注解 - 描述方法参数
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface AIParam {
    /**
     * 参数名称（AI识别的名称）
     */
    String name();

    /**
     * 参数描述
     */
    String description();

    /**
     * 是否必需
     */
    boolean required() default true;

    /**
     * 参数类型
     */
    String type() default "string";

    /**
     * 枚举值（可选值列表）
     */
    String[] enumValues() default {};

}
