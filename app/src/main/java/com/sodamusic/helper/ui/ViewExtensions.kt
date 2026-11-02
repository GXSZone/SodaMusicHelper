package com.sodamusic.helper.ui

import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes

/** 可见性：view.isVisible = hasLogs，比到处写 VISIBLE/GONE 三元表达式更直观。 */
var View.isVisible: Boolean
    get() = visibility == View.VISIBLE
    set(value) {
        visibility = if (value) View.VISIBLE else View.GONE
    }

/** 资源 id 直接设置文本，省掉一层 getString。 */
fun TextView.setTextRes(@StringRes resId: Int) {
    setText(resId)
}
