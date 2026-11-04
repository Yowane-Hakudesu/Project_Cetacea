package com.whalechan.protocol

import org.json.JSONObject

/**
 * 手机端（服务端）与伴侣端（客户端）之间的 JSON 协议 —— 两端共享。
 *
 * 握手：
 *   C→S {"type":"hello","token":"XXXX","client":"companion","protocol":1}
 *   S→C {"type":"hello_ok","device":"...","version":"0.3.0","protocol":1}
 *
 * 指令：
 *   C→S {"type":"chat","mode":"TASK"|"CHAT_ONLY","text":"..."}
 *   C→S {"type":"stop"}
 *   C→S {"type":"confirm_result","id":"...","approved":true}
 *   C→S {"type":"attachment","msgId":"...","kind":"image"|"file","name":"...",
 *        "mime":"image/jpeg","size":12345,"data":"<base64>","ts":...}
 *
 * 事件：
 *   S→C {"type":"state","taskId":"...","status":"...","step":3,"maxSteps":50}
 *   S→C {"type":"message","role":"assistant","text":"...","taskId":"..."}
 *   S→C {"type":"step","record":{...}}
 *   S→C {"type":"confirm_request","id":"...","action":"...","reason":"...","detail":"..."}
 *   S→C {"type":"task_end","taskId":"...","ok":true,"summary":"..."}
 *   S→C {"type":"error","code":"...","message":"..."}
 */
object Protocol {

    const val VERSION = 1
    const val APP_VERSION = "0.3.1"

    const val TYPE_HELLO = "hello"
    const val TYPE_HELLO_OK = "hello_ok"
    const val TYPE_CHAT = "chat"
    const val TYPE_STOP = "stop"
    const val TYPE_CONFIRM_RESULT = "confirm_result"
    const val TYPE_CONFIRM_REQUEST = "confirm_request"
    const val TYPE_STATE = "state"
    const val TYPE_MESSAGE = "message"
    const val TYPE_STEP = "step"
    const val TYPE_TASK_END = "task_end"
    const val TYPE_NOTICE = "notice"
    const val TYPE_ERROR = "error"
    const val TYPE_PING = "ping"
    const val TYPE_PONG = "pong"
    const val TYPE_ATTACHMENT = "attachment"

    const val MODE_TASK = "TASK"
    const val MODE_CHAT_ONLY = "CHAT_ONLY"

    const val KIND_IMAGE = "image"
    const val KIND_FILE = "file"

    /** 单个附件解码后的上限：超过就拒绝，避免 WS 帧过大导致 OOM。 */
    const val MAX_ATTACHMENT_BYTES = 4 * 1024 * 1024

    /**
     * WebSocket 文本帧上限。base64 后体积约为原始的 4/3，4MB 附件需要 ~5.4MB，
     * 这里留到 8MB；同时两端都必须显式 setMaxFramePayloadLength / setMaxTextMessageSize，
     * 否则 Java-WebSocket 默认的 64KB 帧上限会直接拒绝大附件。
     */
    const val MAX_WS_TEXT_BYTES = 8 * 1024 * 1024

    fun hello(token: String, client: String): JSONObject = JSONObject().apply {
        put("type", TYPE_HELLO)
        put("token", token)
        put("client", client)
        put("protocol", VERSION)
    }

    fun helloOk(device: String): JSONObject = JSONObject().apply {
        put("type", TYPE_HELLO_OK)
        put("device", device)
        put("version", APP_VERSION)
        put("protocol", VERSION)
    }

    fun pong(): JSONObject = JSONObject().apply {
        put("type", TYPE_PONG)
        put("ts", System.currentTimeMillis())
    }

    fun chat(text: String, mode: String): JSONObject = JSONObject().apply {
        put("type", TYPE_CHAT)
        put("text", text)
        put("mode", mode)
        put("ts", System.currentTimeMillis())
    }

    fun stop(): JSONObject = JSONObject().apply {
        put("type", TYPE_STOP)
        put("ts", System.currentTimeMillis())
    }

    fun confirmResult(id: String, approved: Boolean): JSONObject = JSONObject().apply {
        put("type", TYPE_CONFIRM_RESULT)
        put("id", id)
        put("approved", approved)
    }

    fun state(
        taskId: String,
        status: String,
        step: Int,
        maxSteps: Int,
        message: String
    ): JSONObject = JSONObject().apply {
        put("type", TYPE_STATE)
        put("taskId", taskId)
        put("status", status)
        put("step", step)
        put("maxSteps", maxSteps)
        put("message", message)
        put("ts", System.currentTimeMillis())
    }

    fun message(role: String, text: String, taskId: String): JSONObject = JSONObject().apply {
        put("type", TYPE_MESSAGE)
        put("role", role)
        put("text", text)
        put("taskId", taskId)
        put("ts", System.currentTimeMillis())
    }

    /** record 是 StepLogEntity.toJson() 的结果，由手机端构造。 */
    fun step(record: JSONObject): JSONObject = JSONObject().apply {
        put("type", TYPE_STEP)
        put("record", record)
    }

    fun confirmRequest(id: String, action: String, reason: String, detail: String): JSONObject =
        JSONObject().apply {
            put("type", TYPE_CONFIRM_REQUEST)
            put("id", id)
            put("action", action)
            put("reason", reason)
            put("detail", detail)
            put("ts", System.currentTimeMillis())
        }

    fun taskEnd(taskId: String, ok: Boolean, summary: String): JSONObject = JSONObject().apply {
        put("type", TYPE_TASK_END)
        put("taskId", taskId)
        put("ok", ok)
        put("summary", summary)
        put("ts", System.currentTimeMillis())
    }

    fun notice(text: String): JSONObject = JSONObject().apply {
        put("type", TYPE_NOTICE)
        put("text", text)
        put("ts", System.currentTimeMillis())
    }

    fun error(code: String, message: String): JSONObject = JSONObject().apply {
        put("type", TYPE_ERROR)
        put("code", code)
        put("message", message)
        put("ts", System.currentTimeMillis())
    }

    fun attachment(
        msgId: String,
        kind: String,
        name: String,
        mime: String,
        size: Int,
        base64: String
    ): JSONObject = JSONObject().apply {
        put("type", TYPE_ATTACHMENT)
        put("msgId", msgId)
        put("kind", kind)
        put("name", name)
        put("mime", mime)
        put("size", size)
        put("data", base64)
        put("ts", System.currentTimeMillis())
    }
}
