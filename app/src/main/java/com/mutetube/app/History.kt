package com.mutetube.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Recently played videos (newest first), kept in SharedPreferences. */
object History {
    /** [uri] is set for normal videos (files / direct links); [id] is then a local key. */
    data class Item(val id: String, val title: String, val channel: String, val durationSec: Long, val time: Long, val uri: String = "")

    private const val PREFS = "history"
    private const val KEY = "items"
    private const val MAX = 200

    @Synchronized
    fun all(context: Context): List<Item> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val uri = o.optString("uri")
                val id = if (uri.isNotBlank()) o.optString("id").ifBlank { null } else YouTubeId.parse(o.optString("id"))
                id ?: return@mapNotNull null
                Item(id, o.optString("title"), o.optString("channel"), o.optLong("duration"), o.optLong("time"), uri)
            }
        } catch (_: Exception) {
            emptyList() // corrupted data: start fresh rather than crash
        }
    }

    /** Adds or moves a video to the top; empty fields keep their previous value. */
    @Synchronized
    fun put(context: Context, id: String, title: String = "", channel: String = "", durationSec: Long = 0, uri: String = "") {
        val list = all(context).toMutableList()
        val old = list.firstOrNull { it.id == id }
        list.removeAll { it.id == id }
        list.add(0, Item(
            id,
            title.ifBlank { old?.title.orEmpty() },
            channel.ifBlank { old?.channel.orEmpty() },
            if (durationSec > 0) durationSec else old?.durationSec ?: 0,
            System.currentTimeMillis(),
            uri.ifBlank { old?.uri.orEmpty() },
        ))
        save(context, list.take(MAX))
    }

    /** Stable key for a normal video's history entry and saved position. */
    fun keyFor(uri: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
        return "f_" + digest.take(10).joinToString("") { "%02x".format(it) }
    }

    /** Updates details without moving the item. */
    @Synchronized
    fun update(context: Context, id: String, title: String, channel: String, durationSec: Long) {
        val list = all(context)
        if (list.none { it.id == id }) return put(context, id, title, channel, durationSec)
        save(context, list.map {
            if (it.id != id) it else it.copy(
                title = title.ifBlank { it.title },
                channel = channel.ifBlank { it.channel },
                durationSec = if (durationSec > 0) durationSec else it.durationSec,
            )
        })
    }

    fun title(context: Context, id: String): String = all(context).firstOrNull { it.id == id }?.title.orEmpty()

    @Synchronized
    fun remove(context: Context, id: String) = save(context, all(context).filter { it.id != id })

    @Synchronized
    fun clear(context: Context) = prefs(context).edit().remove(KEY).apply()

    private fun save(context: Context, list: List<Item>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("channel", it.channel)
                .put("duration", it.durationSec).put("time", it.time).put("uri", it.uri))
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
