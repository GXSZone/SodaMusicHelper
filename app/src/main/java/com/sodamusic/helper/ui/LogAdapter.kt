package com.sodamusic.helper.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.sodamusic.helper.R
import com.sodamusic.helper.automation.LogEntry
import com.sodamusic.helper.automation.LogLevel
import com.sodamusic.helper.databinding.ItemLogBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 按 id 做增量刷新：日志是「新条目插到最前」，用 DiffUtil 避免整表重绘闪烁。 */
private val LOG_DIFF = object : DiffUtil.ItemCallback<LogEntry>() {
    override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean = oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean = oldItem == newItem
}

/**
 * 运行日志适配器。
 * 时间按 HH:mm:ss 渲染，级别用 R.color.log_* 着色（圆点始终按级别上色，WARN/ERROR 消息也上色）。
 */
class LogAdapter : ListAdapter<LogEntry, LogAdapter.LogViewHolder>(LOG_DIFF) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LogViewHolder(private val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: LogEntry) {
            val context = binding.root.context
            binding.logTime.text = timeFormat.format(Date(entry.timeMillis))
            binding.logMessage.text = entry.message

            val levelColor = ContextCompat.getColor(context, colorResOf(entry.level))
            // 8dp 圆点：按级别着色的实心圆（drawable 目录不在 UI 改动范围内，因此代码生成）
            binding.logLevelDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(levelColor)
            }

            // WARN/ERROR 用级别色强调，其余保持正文色，保证深浅色主题下都可读
            val bodyColor = MaterialColors.getColor(binding.logMessage, com.google.android.material.R.attr.colorOnSurface)
            binding.logMessage.setTextColor(
                when (entry.level) {
                    LogLevel.WARN, LogLevel.ERROR -> levelColor
                    LogLevel.INFO, LogLevel.ACTION, LogLevel.SUCCESS -> bodyColor
                }
            )
        }

        private fun colorResOf(level: LogLevel): Int = when (level) {
            LogLevel.INFO, LogLevel.SUCCESS -> R.color.log_info
            LogLevel.ACTION -> R.color.log_action
            LogLevel.WARN -> R.color.log_warn
            LogLevel.ERROR -> R.color.log_error
        }
    }
}
