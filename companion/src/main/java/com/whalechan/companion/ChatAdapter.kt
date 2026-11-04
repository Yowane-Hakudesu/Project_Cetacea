package com.whalechan.companion

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 聊天列表适配器：
 *  - 左侧：鲸鱼娘（白气泡 + 头像）
 *  - 右侧：我（蓝气泡，无头像）
 *  - 支持文本 / 图片 / 文件 / 时间分隔 / 系统提示
 */
class ChatAdapter(private val context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<ChatItem>()
    private val imageExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun add(item: ChatItem) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    fun lastTimestamp(): Long = items.lastOrNull { it !is ChatItem.Time }?.ts ?: 0L

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is ChatItem.Time -> TYPE_TIME
        is ChatItem.System -> TYPE_SYSTEM
        else -> TYPE_CHAT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_TIME -> TimeHolder(inflater.inflate(R.layout.item_chat_time, parent, false))
            TYPE_SYSTEM -> SystemHolder(inflater.inflate(R.layout.item_chat_system, parent, false))
            else -> ChatHolder(inflater.inflate(R.layout.item_chat, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ChatItem.Time -> (holder as TimeHolder).tvTime.text = formatTime(item.ts)
            is ChatItem.System -> (holder as SystemHolder).tvSystem.text = item.text
            else -> bindChat(holder as ChatHolder, item)
        }
    }

    fun shutdown() {
        imageExecutor.shutdownNow()
    }

    private fun bindChat(holder: ChatHolder, item: ChatItem) {
        val mine = when (item) {
            is ChatItem.Text -> item.mine
            is ChatItem.Image -> item.mine
            is ChatItem.File -> item.mine
            else -> false
        }

        holder.rowRoot.gravity = if (mine) Gravity.END else Gravity.START
        holder.avatar.visibility = if (mine) View.GONE else View.VISIBLE
        holder.bubble.setBackgroundResource(
            if (mine) R.drawable.bg_bubble_right else R.drawable.bg_bubble_left
        )

        val textColor = if (mine) Color.WHITE else Color.parseColor("#14203A")
        val metaColor = if (mine) Color.parseColor("#D6E2FF") else Color.parseColor("#5A6B8C")
        val maxTextWidth = (context.resources.displayMetrics.widthPixels * 0.66f).toInt()

        holder.text.visibility = View.GONE
        holder.image.visibility = View.GONE
        holder.image.setImageDrawable(null)
        holder.image.tag = null
        holder.fileRow.visibility = View.GONE
        holder.text.setTextColor(textColor)
        holder.fileName.setTextColor(textColor)
        holder.fileSize.setTextColor(metaColor)
        holder.meta.setTextColor(metaColor)

        when (item) {
            is ChatItem.Text -> {
                holder.text.visibility = View.VISIBLE
                holder.text.text = item.text
                holder.text.maxWidth = maxTextWidth
            }

            is ChatItem.Image -> {
                holder.image.visibility = View.VISIBLE
                loadImage(holder.image, item.path)
            }

            is ChatItem.File -> {
                holder.fileRow.visibility = View.VISIBLE
                holder.fileName.text = item.name
                holder.fileName.maxWidth = maxTextWidth - dp(50)
                holder.fileSize.text = AttachmentCodec.humanSize(item.size)
            }

            else -> Unit
        }

        holder.meta.text = timeFormat.format(Date(item.ts)) + if (mine) " · 已发送" else ""
    }

    private fun loadImage(view: ImageView, path: String?) {
        view.tag = path
        if (path == null) {
            view.setImageResource(R.drawable.ic_file)
            return
        }
        imageExecutor.execute {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            val target = dp(260)
            while (bounds.outWidth / sample > target * 2) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeFile(path, options)
            mainHandler.post {
                if (view.tag == path) {
                    if (bitmap != null) view.setImageBitmap(bitmap)
                    else view.setImageResource(R.drawable.ic_file)
                }
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun formatTime(ts: Long): String {
        val now = System.currentTimeMillis()
        val sameDay = now - ts < 24 * 60 * 60 * 1000L
        return if (sameDay) {
            timeFormat.format(Date(ts))
        } else {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
        }
    }

    class ChatHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rowRoot: LinearLayout = view.findViewById(R.id.rowRoot)
        val avatar: ImageView = view.findViewById(R.id.ivAvatar)
        val bubble: LinearLayout = view.findViewById(R.id.bubbleContainer)
        val text: TextView = view.findViewById(R.id.tvText)
        val image: ImageView = view.findViewById(R.id.ivImage)
        val fileRow: LinearLayout = view.findViewById(R.id.llFile)
        val fileName: TextView = view.findViewById(R.id.tvFileName)
        val fileSize: TextView = view.findViewById(R.id.tvFileSize)
        val meta: TextView = view.findViewById(R.id.tvMeta)
    }

    class TimeHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tvTime)
    }

    class SystemHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSystem: TextView = view.findViewById(R.id.tvSystem)
    }

    companion object {
        private const val TYPE_TIME = 0
        private const val TYPE_SYSTEM = 1
        private const val TYPE_CHAT = 2
    }
}
