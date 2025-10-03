package com.example.realtalkenglishwithAI.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlin.random.Random

// --- 1. Feature Flags ---

/**
 * Định nghĩa tất cả các lá cờ tính năng trong ứng dụng.
 * Việc dùng enum giúp code an toàn và dễ quản lý hơn.
 */
enum class FeatureFlag(val key: String, val defaultValue: Boolean) {
    HYBRID_SYSTEM("hybrid_system_enabled", true),
    CHEAP_COLLECTOR("cheap_collector_enabled", true),
    STRICT_CORRECTION("strict_correction_enabled", true)
}

/**
 * Interface chung để cung cấp trạng thái của một feature flag.
 */
interface FeatureFlagProvider {
    fun isEnabled(flag: FeatureFlag): Boolean
}

/**
 * Một implementation đơn giản, lưu trạng thái các lá cờ trong bộ nhớ.
 * Trạng thái mặc định của lá cờ được đọc từ `defaultValue` trong enum.
 */
class LocalFeatureFlagController : FeatureFlagProvider {
    private val flags = mutableMapOf<String, Boolean>()

    override fun isEnabled(flag: FeatureFlag): Boolean {
        // Luôn sử dụng giá trị mặc định được định nghĩa trong enum
        return flag.defaultValue
    }

    fun setFlag(flag: FeatureFlag, enabled: Boolean) {
        flags[flag.key] = enabled
    }

    fun resetAll() {
        flags.clear()
    }
}


// --- 2. Metrics Collection ---

/**
 * Interface chung để phát ra các số liệu (metrics).
 */
interface MetricsEmitter {
    fun emit(metricName: String, params: Map<String, Any>)
}

/**
 * Một implementation đơn giản, chỉ in các số liệu ra Logcat.
 * Rất hữu ích cho việc gỡ lỗi và kiểm tra ban đầu.
 */
class LogcatMetricsCollector(private val tag: String = "AppMetrics") : MetricsEmitter {
    override fun emit(metricName: String, params: Map<String, Any>) {
        val formattedParams = params.entries.joinToString(", ") { "${it.key}=${it.value}" }
        Log.d(tag, "Metric: \'$metricName\' | $formattedParams")
    }
}
