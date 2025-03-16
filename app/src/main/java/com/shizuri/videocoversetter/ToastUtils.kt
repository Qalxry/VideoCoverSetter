package com.shizuri.videocoversetter

import android.content.Context
import android.widget.Toast

class ToastUtil(context: Context) {
    private var mToast: Toast? = null
    private val mContext: Context

    // 通过构造函数注入Context
    init {
        mContext = context
    }

    fun showToast(message: String, duration: Int = Toast.LENGTH_LONG) {
        if (mToast != null) {
            mToast!!.cancel()
        }
        mToast = Toast.makeText(mContext, message, duration)
        mToast?.show()
    }

    fun showToast(stringId: Int, duration: Int = Toast.LENGTH_LONG) {
        if (mToast != null) {
            mToast!!.cancel()
        }
        mToast = Toast.makeText(mContext, stringId, duration)
        mToast?.show()
    }
}