package com.pux.camaratv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView

/** Lists the configured cameras: open, edit, delete, add one by hand or find them on the network. */
class CamerasActivity : Activity() {

    private lateinit var list: ListView
    private lateinit var empty: TextView
    private var cameras = emptyList<Camera>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cameras)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)

        findViewById<View>(R.id.add).setOnClickListener {
            startActivity(Intent(this, CameraEditActivity::class.java))
        }
        findViewById<View>(R.id.scan).setOnClickListener {
            startActivity(Intent(this, ScanActivity::class.java))
        }
        list.setOnItemClickListener { _, _, position, _ -> showActions(cameras[position]) }
    }

    override fun onResume() {
        super.onResume()
        cameras = CameraStore.all(this)
        empty.visibility = if (cameras.isEmpty()) View.VISIBLE else View.GONE
        list.adapter = SimpleAdapter(
            this,
            cameras.mapIndexed { i, c -> mapOf("title" to "${i + 1}. ${c.name}", "detail" to "${c.host}:${c.port} · usuario ${c.user}") },
            android.R.layout.simple_list_item_2,
            arrayOf("title", "detail"),
            intArrayOf(android.R.id.text1, android.R.id.text2),
        )
    }

    private fun showActions(camera: Camera) {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(camera.name)
            .setItems(arrayOf("Ver", "Editar", "Eliminar")) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_CAMERA_ID, camera.id)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                    1 -> startActivity(Intent(this, CameraEditActivity::class.java).putExtra(CameraEditActivity.EXTRA_ID, camera.id))
                    2 -> confirmDelete(camera)
                }
            }
            .show()
    }

    private fun confirmDelete(camera: Camera) {
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("¿Eliminar ${camera.name}?")
            .setPositiveButton("Eliminar") { _, _ ->
                CameraStore.delete(this, camera.id)
                onResume()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
