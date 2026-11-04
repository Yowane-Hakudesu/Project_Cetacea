package com.whalechan.companion

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.whalechan.protocol.CompanionClient
import com.whalechan.protocol.NsdDiscovery
import com.whalechan.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 独立伴侣端：连接手机端的 WebSocket，提供类似主流 IM 的聊天界面。
 *
 * 支持：文本（聊天/任务两种模式）、图片、文件、远程确认、自定义聊天背景。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var ivChatBackground: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var rvMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnAttach: ImageButton
    private lateinit var btnMenu: ImageButton
    private lateinit var tvTaskMode: TextView

    private lateinit var adapter: ChatAdapter
    private lateinit var background: ChatBackground

    private var client: CompanionClient? = null
    private var dialogDiscovery: NsdDiscovery? = null
    private var taskMode = false
    private var confirmDialog: AlertDialog? = null

    private val prefs by lazy { getSharedPreferences("companion", Context.MODE_PRIVATE) }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { sendImage(it) }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { sendFile(it) }
    }

    private val pickBackground = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { applyNewBackground(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_companion_chat)

        bindViews()
        background = ChatBackground(this)

        adapter = ChatAdapter(this)
        rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rvMessages.adapter = adapter

        applyBackground()
        wireActions()
        updateTaskMode()

        appendSystem("伴侣模式：先在手机端「设置 → 远程伴侣」开启服务端，再点上方状态栏填 IP 与配对码。")
        autoConnect()
    }

    override fun onDestroy() {
        dialogDiscovery?.stop()
        dialogDiscovery = null
        confirmDialog?.dismiss()
        client?.shutdown()
        client = null
        adapter.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        ivChatBackground = findViewById(R.id.ivChatBackground)
        tvStatus = findViewById(R.id.tvStatus)
        rvMessages = findViewById(R.id.rvMessages)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnAttach = findViewById(R.id.btnAttach)
        btnMenu = findViewById(R.id.btnMenu)
        tvTaskMode = findViewById(R.id.tvTaskMode)
    }

    private fun wireActions() {
        tvStatus.setOnClickListener { showConnectDialog() }
        btnMenu.setOnClickListener { showMenu() }
        btnSend.setOnClickListener { sendText() }
        btnAttach.setOnClickListener { showAttachmentChooser() }
        tvTaskMode.setOnClickListener {
            taskMode = !taskMode
            updateTaskMode()
        }
    }

    private fun updateTaskMode() {
        tvTaskMode.text = getString(if (taskMode) R.string.task_mode_on else R.string.task_mode_off)
        tvTaskMode.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (taskMode) 0xFFD6E4FF.toInt() else 0xFFE8F0FF.toInt()
        )
    }

    // ────────────────────────── 连接 ──────────────────────────

    private fun autoConnect() {
        val host = prefs.getString(KEY_HOST, null)
        val token = prefs.getString(KEY_TOKEN, null)
        if (!host.isNullOrBlank() && !token.isNullOrBlank()) {
            connect(host, token)
        }
    }

    private fun showConnectDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_connect, null)
        val etHost = view.findViewById<EditText>(R.id.etDialogHost)
        val etToken = view.findViewById<EditText>(R.id.etDialogToken)
        val btnDiscover = view.findViewById<Button>(R.id.btnDialogDiscover)
        val tvDialogStatus = view.findViewById<TextView>(R.id.tvDialogStatus)

        etHost.setText(prefs.getString(KEY_HOST, ""))
        etToken.setText(prefs.getString(KEY_TOKEN, ""))

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.connect_title)
            .setView(view)
            .setPositiveButton(R.string.connect_action, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        btnDiscover.setOnClickListener {
            tvDialogStatus.text = "搜索中…"
            dialogDiscovery?.stop()
            val discovery = NsdDiscovery(
                context = this,
                onFound = { name, host, port ->
                    runOnUiThread {
                        etHost.setText("$host:$port")
                        tvDialogStatus.text = "发现 $name → $host:$port"
                    }
                },
                onLost = { name -> runOnUiThread { tvDialogStatus.text = "设备消失：$name" } }
            )
            dialogDiscovery = discovery
            discovery.start()
            btnDiscover.postDelayed({
                discovery.stop()
                if (dialogDiscovery === discovery) dialogDiscovery = null
                if (tvDialogStatus.text == "搜索中…") {
                    tvDialogStatus.text = "没搜到设备（可手动输入 IP）"
                }
            }, 6000L)
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = etHost.text.toString().trim()
                val token = etToken.text.toString().trim()
                if (host.isEmpty() || token.isEmpty()) {
                    tvDialogStatus.text = "地址和配对码都要填"
                    return@setOnClickListener
                }
                prefs.edit().putString(KEY_HOST, host).putString(KEY_TOKEN, token).apply()
                dialog.dismiss()
                connect(host, token)
            }
        }
        dialog.setOnDismissListener {
            dialogDiscovery?.stop()
            dialogDiscovery = null
        }
        dialog.show()
    }

    private fun connect(host: String, token: String) {
        disconnect()
        updateStatus(getString(R.string.status_connecting))

        val hostPort = if (host.contains(':')) host else "$host:8787"
        val listener = object : CompanionClient.Listener {
            override fun onConnected() {
                runOnUiThread { updateStatus(getString(R.string.status_connecting)) }
            }

            override fun onDisconnected(reason: String) {
                runOnUiThread {
                    updateStatus(getString(R.string.status_disconnected))
                    appendSystem("连接断开：$reason")
                }
            }

            override fun onMessage(json: JSONObject) {
                runOnUiThread { handleServerMessage(json) }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    updateStatus(getString(R.string.status_error))
                    appendSystem("连接错误：$message")
                }
            }
        }

        val c = runCatching { CompanionClient("ws://$hostPort", token, listener) }.getOrElse {
            updateStatus(getString(R.string.status_error))
            appendSystem("地址格式不对：${it.message}")
            return
        }
        client = c
        runCatching { c.connect() }.onFailure {
            client = null
            updateStatus(getString(R.string.status_error))
            appendSystem("连接失败：${it.message}")
        }
    }

    private fun disconnect() {
        client?.shutdown()
        client = null
    }

    // ────────────────────────── 服务端消息 ──────────────────────────

    private fun handleServerMessage(json: JSONObject) {
        when (json.optString("type")) {
            Protocol.TYPE_HELLO_OK -> {
                updateStatus(getString(R.string.status_connected, json.optString("device")))
                appendSystem("已连接：${json.optString("device")}")
            }

            Protocol.TYPE_STATE -> {
                val status = json.optString("status")
                val step = json.optInt("step")
                val max = json.optInt("maxSteps")
                val budget = if (max > 0) max.toString() else "∞"
                tvStatus.text = "已连接 · $status" + if (step > 0) " · 第 $step/$budget 步" else ""
            }

            Protocol.TYPE_MESSAGE -> {
                val role = json.optString("role")
                appendText(json.optString("text"), mine = role == "user")
            }

            Protocol.TYPE_CONFIRM_REQUEST -> showConfirm(json)

            Protocol.TYPE_TASK_END -> appendSystem(
                "任务结束：${if (json.optBoolean("ok")) "成功" else "未完成"} · ${json.optString("summary")}"
            )

            Protocol.TYPE_NOTICE -> appendSystem(json.optString("text"))

            Protocol.TYPE_ERROR -> appendSystem(
                "错误：${json.optString("code")} ${json.optString("message")}"
            )

            Protocol.TYPE_PONG, Protocol.TYPE_STEP -> Unit

            else -> Unit
        }
    }

    private fun showConfirm(json: JSONObject) {
        if (isFinishing || isDestroyed) return
        val id = json.optString("id")
        confirmDialog = AlertDialog.Builder(this)
            .setTitle("🐋 ${json.optString("action")}")
            .setMessage("${json.optString("reason")}\n\n${json.optString("detail").take(600)}")
            .setPositiveButton(R.string.confirm_approve) { _, _ ->
                client?.sendConfirm(id, true)
                appendSystem("已确认")
            }
            .setNegativeButton(R.string.confirm_deny) { _, _ ->
                client?.sendConfirm(id, false)
                appendSystem("已拒绝")
            }
            .show()
    }

    // ────────────────────────── 发送 ──────────────────────────

    private fun sendText() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return
        val c = client
        if (c == null) {
            toast(getString(R.string.toast_not_connected))
            showConnectDialog()
            return
        }
        etInput.setText("")
        appendText(text, mine = true)
        val mode = if (taskMode) Protocol.MODE_TASK else Protocol.MODE_CHAT_ONLY
        runCatching { c.sendChat(text, mode) }
            .onFailure { appendSystem("发送失败：${it.message}") }
    }

    private fun showAttachmentChooser() {
        if (client == null) {
            toast(getString(R.string.toast_not_connected))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.attach_title)
            .setItems(
                arrayOf(getString(R.string.attach_image), getString(R.string.attach_file))
            ) { _, which ->
                if (which == 0) pickImage.launch("image/*") else pickFile.launch(arrayOf("*/*"))
            }
            .show()
    }

    private fun sendImage(uri: Uri) {
        val c = client ?: return
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                AttachmentCodec.compressImage(this@MainActivity, uri)
            }
            if (bytes == null || bytes.size > Protocol.MAX_ATTACHMENT_BYTES) {
                toast(getString(R.string.toast_image_too_large))
                return@launch
            }
            val name = "IMG_${System.currentTimeMillis()}.jpg"
            val local = withContext(Dispatchers.IO) {
                val dir = File(filesDir, "sent").apply { mkdirs() }
                File(dir, name).apply { writeBytes(bytes) }
            }
            appendImage(local.absolutePath, name, mine = true)
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            runCatching {
                c.sendAttachment(
                    UUID.randomUUID().toString(),
                    Protocol.KIND_IMAGE,
                    name,
                    "image/jpeg",
                    bytes.size,
                    base64
                )
            }.onFailure { appendSystem("图片发送失败：${it.message}") }
        }
    }

    private fun sendFile(uri: Uri) {
        val c = client ?: return
        lifecycleScope.launch {
            val displayName = withContext(Dispatchers.IO) {
                AttachmentCodec.displayName(this@MainActivity, uri)
            }
            val bytes = withContext(Dispatchers.IO) {
                AttachmentCodec.readFile(this@MainActivity, uri)
            }
            if (bytes == null || bytes.size > Protocol.MAX_ATTACHMENT_BYTES) {
                toast(getString(R.string.toast_file_too_large))
                return@launch
            }
            val local = withContext(Dispatchers.IO) {
                val dir = File(filesDir, "sent").apply { mkdirs() }
                File(dir, "${System.currentTimeMillis()}_$displayName").apply { writeBytes(bytes) }
            }
            appendFile(local.absolutePath, displayName, bytes.size.toLong(), mine = true)
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            runCatching {
                c.sendAttachment(
                    UUID.randomUUID().toString(),
                    Protocol.KIND_FILE,
                    displayName,
                    "application/octet-stream",
                    bytes.size,
                    base64
                )
            }.onFailure { appendSystem("文件发送失败：${it.message}") }
        }
    }

    // ────────────────────────── 列表渲染 ──────────────────────────

    private fun appendText(text: String, mine: Boolean) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        addTimeIfNeeded(now)
        adapter.add(ChatItem.Text(UUID.randomUUID().toString(), text, mine, now))
        scrollToBottom()
    }

    private fun appendImage(path: String?, name: String, mine: Boolean) {
        val now = System.currentTimeMillis()
        addTimeIfNeeded(now)
        adapter.add(ChatItem.Image(UUID.randomUUID().toString(), path, name, mine, now))
        scrollToBottom()
    }

    private fun appendFile(path: String?, name: String, size: Long, mine: Boolean) {
        val now = System.currentTimeMillis()
        addTimeIfNeeded(now)
        adapter.add(ChatItem.File(UUID.randomUUID().toString(), path, name, size, mine, now))
        scrollToBottom()
    }

    private fun appendSystem(text: String) {
        if (text.isBlank()) return
        adapter.add(ChatItem.System(text, System.currentTimeMillis()))
        scrollToBottom()
    }

    private fun addTimeIfNeeded(ts: Long) {
        val last = adapter.lastTimestamp()
        if (last == 0L || ts - last > 5 * 60 * 1000L) {
            adapter.add(ChatItem.Time(ts))
        }
    }

    private fun scrollToBottom() {
        rvMessages.post {
            if (adapter.itemCount > 0) rvMessages.scrollToPosition(adapter.itemCount - 1)
        }
    }

    // ────────────────────────── 背景与菜单 ──────────────────────────

    private fun applyBackground() {
        val file = background.current()
        if (file != null) {
            ivChatBackground.setImageURI(Uri.fromFile(file))
        } else {
            ivChatBackground.setImageResource(R.drawable.bg_chat_default)
        }
    }

    private fun applyNewBackground(uri: Uri) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { background.setFrom(uri) }
            if (file != null) {
                ivChatBackground.setImageURI(Uri.fromFile(file))
                toast(getString(R.string.toast_bg_set))
            } else {
                toast(getString(R.string.toast_bg_failed))
            }
        }
    }

    private fun resetBackground() {
        background.clear()
        ivChatBackground.setImageResource(R.drawable.bg_chat_default)
        toast(getString(R.string.toast_bg_reset))
    }

    private fun showMenu() {
        val popup = PopupMenu(this, btnMenu)
        popup.menu.add(0, MENU_CONNECT, 0, R.string.menu_connect)
        popup.menu.add(0, MENU_BG, 1, R.string.menu_change_bg)
        popup.menu.add(0, MENU_BG_RESET, 2, R.string.menu_reset_bg)
        popup.menu.add(0, MENU_DISCONNECT, 3, R.string.menu_disconnect)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_CONNECT -> showConnectDialog()
                MENU_BG -> pickBackground.launch("image/*")
                MENU_BG_RESET -> resetBackground()
                MENU_DISCONNECT -> {
                    disconnect()
                    updateStatus(getString(R.string.status_disconnected))
                    appendSystem("已断开连接")
                }
            }
            true
        }
        popup.show()
    }

    private fun updateStatus(text: String) {
        tvStatus.text = text
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val KEY_HOST = "last_host"
        private const val KEY_TOKEN = "last_token"
        private const val MENU_CONNECT = 1
        private const val MENU_BG = 2
        private const val MENU_BG_RESET = 3
        private const val MENU_DISCONNECT = 4
    }
}
