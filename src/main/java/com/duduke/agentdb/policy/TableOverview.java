package com.duduke.agentdb.policy;

/**
 * 一张已授权表的概览：表名、用途说明、可访问列数。
 * <p>
 * list_tables 只需要这些，不需要列的类型和 jOOQ 的 Field 对象 ——
 * 有了这个概览，列清单这一步就完全不用碰数据库元数据，只查授权表本身。
 */
public record TableOverview(String name, String description, int columnCount) {
}
