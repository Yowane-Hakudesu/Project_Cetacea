package com.whalechan.agent.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.whalechan.agent.R
import com.whalechan.agent.core.ConfirmationGate

/**
 * 敏感动作确认页。
 * 只做一件事：把用户的决定交回 ConfirmationGate，然后退出。
 * 用户直接返回/划掉也可以——90 秒超时会按“拒绝”处理。
 */
class ConfirmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_confirm)

        val id = intent.getStringExtra(EXTRA_ID)
        if (id.isNullOrBlank()) {
            finish()
            return
        }

        findViewById<TextView>(R.id.tvConfirmTitle).text =
            intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.confirm_title)
        findViewById<TextView>(R.id.tvConfirmDetail).text =
            intent.getStringExtra(EXTRA_DETAIL).orEmpty()

        findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            ConfirmationGate.deliver(id, true)
            finish()
        }
        findViewById<Button>(R.id.btnDeny).setOnClickListener {
            ConfirmationGate.deliver(id, false)
            finish()
        }
    }

    companion object {
        const val EXTRA_ID = "confirm_id"
        const val EXTRA_TITLE = "confirm_title"
        const val EXTRA_DETAIL = "confirm_detail"
    }
}
