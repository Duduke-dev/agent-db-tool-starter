package com.duduke.agentdb.template.model;

/**
 * 模板参数的类型。
 * <p>
 * 刻意只有这几种：模板参数最终会变成绑定值，而绑定值的类型必须能明确判定 ——
 * 放开成任意字符串就等于把类型判断推给数据库。
 */
public enum ParameterType {

    /** 任意文本 */
    STRING,

    /** 整数或小数 */
    NUMBER,

    /** 日期，形如 2026-09-01 */
    DATE,

    /** 日期时间，形如 2026-09-01 09:00:00 */
    DATETIME,

    /** true / false */
    BOOLEAN
}
