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
 * Trạng thái này sẽ mất khi khởi động lại app, trừ khi được cấu hình bởi ABExperimentManager.
 */
class LocalFeatureFlagController : FeatureFlagProvider {
    private val flags = mutableMapOf<String, Boolean>()

    override fun isEnabled(flag: FeatureFlag): Boolean {
        return flags.getOrDefault(flag.key, flag.defaultValue)
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


// --- 3. A/B Experiment Management ---

/**
 * Định nghĩa các nhóm cho một thử nghiệm A/B.
 */
enum class ExperimentGroup {
    CONTROL,    // Nhóm dùng hệ thống cũ (legacy)
    TREATMENT   // Nhóm dùng hệ thống mới (hybrid)
}

/**
 * Quản lý việc phân nhóm A/B và cấu hình các feature flag tương ứng.
 */
class ABExperimentManager(
    private val sharedPreferences: SharedPreferences,
    private val featureFlags: LocalFeatureFlagController
) {
    private val EXPERIMENT_GROUP_KEY = "ab_experiment_group_v1"

    /**
     * Phân nhóm cho người dùng (nếu chưa có) và cấu hình app.
     * Hàm này nên được gọi một lần khi app khởi động hoặc khi bắt đầu một session.
     */
    fun setupExperiment() {
        val group = getAssignedGroup()
        configureFeatureFlags(group)
    }

    private fun getAssignedGroup(): ExperimentGroup {
        val savedGroup = sharedPreferences.getString(EXPERIMENT_GROUP_KEY, null)
        return when (savedGroup) {
            ExperimentGroup.CONTROL.name -> ExperimentGroup.CONTROL
            ExperimentGroup.TREATMENT.name -> ExperimentGroup.TREATMENT
            else -> {
                // Người dùng mới, phân nhóm ngẫu nhiên với tỷ lệ 50/50
                val newGroup = if (Random.nextBoolean()) ExperimentGroup.TREATMENT else ExperimentGroup.CONTROL
                sharedPreferences.edit().putString(EXPERIMENT_GROUP_KEY, newGroup.name).apply()
                newGroup
            }
        }
    }

    private fun configureFeatureFlags(group: ExperimentGroup) {
        featureFlags.resetAll() // Luôn bắt đầu từ trạng thái sạch
        when (group) {
            ExperimentGroup.CONTROL -> {
                // Người dùng trong nhóm CONTROL sẽ dùng hệ thống cũ.
                featureFlags.setFlag(FeatureFlag.HYBRID_SYSTEM, false)
            }
            ExperimentGroup.TREATMENT -> {
                // Người dùng trong nhóm TREATMENT sẽ dùng hệ thống mới.
                featureFlags.setFlag(FeatureFlag.HYBRID_SYSTEM, true)
                // Chúng ta cũng có thể tắt/bật các phần nhỏ hơn của hệ thống mới nếu cần
                featureFlags.setFlag(FeatureFlag.CHEAP_COLLECTOR, true)
                featureFlags.setFlag(FeatureFlag.STRICT_CORRECTION, true)
            }
        }
    }
}