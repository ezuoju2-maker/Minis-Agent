package com.openminis.app.clientfrail

import android.content.Context
import android.content.SharedPreferences

/**
 * 破甲层的跨进程状态落盘。
 *
 * 只存三样东西：轮换游标、是否已建立合作关系、连续拒答数。都是小整数与布尔，
 * 不碰任何业务数据。进程被系统回收后，状态机从磁盘接回原来的位置——否则每次
 * 冷启动都从 OPENING 重新来一遍，同样的开场形态连着出现，本身就是最容易被
 * 识别的签名。
 */
internal object FrailStore {

    private const val FILE = "frail_state"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun prefs(context: Context? = null): SharedPreferences? {
        val p = prefs
        if (p != null) return p
        val ctx = context ?: return null
        val created = ctx.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = created
        return created
    }

    fun getInt(p: SharedPreferences?, key: String, def: Int): Int =
        p?.getInt(key, def) ?: def

    fun putInt(p: SharedPreferences?, key: String, value: Int) {
        p?.edit()?.putInt(key, value)?.apply()
    }

    fun getFloat(p: SharedPreferences?, key: String, def: Float): Float =
        p?.getFloat(key, def) ?: def

    fun putFloat(p: SharedPreferences?, key: String, value: Float) {
        p?.edit()?.putFloat(key, value)?.apply()
    }

    fun getBool(p: SharedPreferences?, key: String, def: Boolean): Boolean =
        p?.getBoolean(key, def) ?: def

    fun putBool(p: SharedPreferences?, key: String, value: Boolean) {
        p?.edit()?.putBoolean(key, value)?.apply()
    }

    fun getString(p: SharedPreferences?, key: String, def: String?): String? =
        p?.getString(key, def) ?: def

    fun putString(p: SharedPreferences?, key: String, value: String) {
        p?.edit()?.putString(key, value)?.apply()
    }

    fun remove(p: SharedPreferences?, key: String) {
        p?.edit()?.remove(key)?.apply()
    }
}
