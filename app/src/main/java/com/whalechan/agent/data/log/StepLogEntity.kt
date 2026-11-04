package com.whalechan.agent.data.log

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONObject

/**
 * 一条结构化的执行日志。
 *
 * 每完成一步写一条，字段与伴侣端时间线一一对应：
 *   timestamp / taskId / stepIndex / actionType / actionPayload /
 *   screenFingerprintBefore / screenFingerprintAfter / result /
 *   errorCategory / errorDetail / llmRawResponse / uiSummary
 *
 * 不存截图（除非以后显式开启），uiSummary 存的是裁剪后的节点摘要，
 * 足够回答“当时屏幕上有什么”，又不会把整块屏幕内容落盘。
 */
@Entity(
    tableName = "step_logs",
    indices = [Index("taskId"), Index("timestamp")]
)
data class StepLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val taskId: String,
    val timestamp: Long,
    val stepIndex: Int,
    val goal: String,
    val actionType: String,
    val actionPayload: String,
    val screenFingerprintBefore: String,
    val screenFingerprintAfter: String,
    val llmRawResponse: String,
    /** success | fail | denied | skipped */
    val result: String,
    val errorCategory: String,
    val errorDetail: String,
    val uiSummary: String
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("taskId", taskId)
        put("timestamp", timestamp)
        put("stepIndex", stepIndex)
        put("goal", goal)
        put("actionType", actionType)
        put("actionPayload", actionPayload)
        put("screenFingerprintBefore", screenFingerprintBefore)
        put("screenFingerprintAfter", screenFingerprintAfter)
        put("llmRawResponse", llmRawResponse)
        put("result", result)
        put("errorCategory", errorCategory)
        put("errorDetail", errorDetail)
        put("uiSummary", uiSummary)
    }

    companion object {
        fun fromJson(json: JSONObject): StepLogEntity = StepLogEntity(
            id = json.optLong("id"),
            taskId = json.optString("taskId"),
            timestamp = json.optLong("timestamp"),
            stepIndex = json.optInt("stepIndex"),
            goal = json.optString("goal"),
            actionType = json.optString("actionType"),
            actionPayload = json.optString("actionPayload"),
            screenFingerprintBefore = json.optString("screenFingerprintBefore"),
            screenFingerprintAfter = json.optString("screenFingerprintAfter"),
            llmRawResponse = json.optString("llmRawResponse"),
            result = json.optString("result"),
            errorCategory = json.optString("errorCategory"),
            errorDetail = json.optString("errorDetail"),
            uiSummary = json.optString("uiSummary")
        )

        fun success(
            taskId: String,
            stepIndex: Int,
            goal: String,
            actionType: String,
            actionPayload: String,
            before: String,
            after: String,
            llmRaw: String,
            uiSummary: String
        ) = StepLogEntity(
            taskId = taskId,
            timestamp = System.currentTimeMillis(),
            stepIndex = stepIndex,
            goal = goal,
            actionType = actionType,
            actionPayload = actionPayload,
            screenFingerprintBefore = before,
            screenFingerprintAfter = after,
            llmRawResponse = llmRaw,
            result = "success",
            errorCategory = "",
            errorDetail = "",
            uiSummary = uiSummary
        )

        fun failure(
            taskId: String,
            stepIndex: Int,
            goal: String,
            actionType: String,
            actionPayload: String,
            before: String,
            after: String,
            llmRaw: String,
            detail: String,
            category: ErrorCategory,
            result: String = "fail",
            uiSummary: String = ""
        ) = StepLogEntity(
            taskId = taskId,
            timestamp = System.currentTimeMillis(),
            stepIndex = stepIndex,
            goal = goal,
            actionType = actionType,
            actionPayload = actionPayload,
            screenFingerprintBefore = before,
            screenFingerprintAfter = after,
            llmRawResponse = llmRaw,
            result = result,
            errorCategory = category.name,
            errorDetail = detail.take(1500),
            uiSummary = uiSummary
        )
    }
}
