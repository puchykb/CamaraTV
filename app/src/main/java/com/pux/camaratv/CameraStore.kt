package com.pux.camaratv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Camera(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
)

/** The list of cameras, kept in the app's private preferences. */
object CameraStore {

    private fun prefs(context: Context) = context.getSharedPreferences("cameras", Context.MODE_PRIVATE)

    fun all(context: Context): List<Camera> {
        val raw = prefs(context).getString("list", null)
        if (raw == null) {
            // First run: start with the camera configured at build time, if any
            val seed = if (BuildConfig.CAM_HOST.isNotEmpty()) {
                listOf(Camera(newId(), "Cámara 1", BuildConfig.CAM_HOST, BuildConfig.CAM_PORT, BuildConfig.CAM_USER, BuildConfig.CAM_PASS))
            } else {
                emptyList()
            }
            write(context, seed)
            return seed
        }
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Camera(
                o.getString("id"),
                o.optString("name", "Cámara ${i + 1}"),
                o.getString("host"),
                o.optInt("port", DEFAULT_PORT),
                o.optString("user", "admin"),
                o.optString("password", ""),
            )
        }
    }

    fun get(context: Context, id: String?): Camera? = all(context).firstOrNull { it.id == id }

    fun save(context: Context, camera: Camera) {
        val list = all(context).toMutableList()
        val index = list.indexOfFirst { it.id == camera.id }
        if (index >= 0) list[index] = camera else list += camera
        write(context, list)
        onChanged(context)
    }

    fun delete(context: Context, id: String) {
        write(context, all(context).filterNot { it.id == id })
        onChanged(context)
    }

    fun lastViewed(context: Context): String? = prefs(context).getString("last_viewed", null)

    fun setLastViewed(context: Context, id: String) = prefs(context).edit().putString("last_viewed", id).apply()

    fun newId(): String = UUID.randomUUID().toString()

    private fun write(context: Context, list: List<Camera>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("host", it.host)
                    .put("port", it.port)
                    .put("user", it.user)
                    .put("password", it.password),
            )
        }
        prefs(context).edit().putString("list", array.toString()).apply()
    }

    /** The background watcher keeps one connection per camera, so it must pick up the new list. */
    private fun onChanged(context: Context) {
        if (AlertSettings.mode(context) != AlertSettings.Mode.OFF) MotionService.start(context)
    }

    const val DEFAULT_PORT = 34567
}
