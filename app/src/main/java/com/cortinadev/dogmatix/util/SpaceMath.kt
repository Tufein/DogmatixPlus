package com.cortinadev.dogmatix.util

/** Saturating estimates: a very large or malformed source size can never wrap into free space. */
object SpaceMath {
    fun add(a: Long, b: Long): Long {
        val x = a.coerceAtLeast(0); val y = b.coerceAtLeast(0)
        return if (x > Long.MAX_VALUE - y) Long.MAX_VALUE else x + y
    }
    fun sum(values: Iterable<Long>): Long = values.fold(0L, ::add)
    fun need(size: Long, extractable: Boolean): Long = if (extractable) add(size, size) else size.coerceAtLeast(0)
    fun available(free: Long, reserve: Long, margin: Long): Long =
        (free.coerceAtLeast(0) - reserve.coerceAtLeast(0)).coerceAtLeast(0) - margin.coerceAtLeast(0)
}
