package dev.nisarg.paisa.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import dev.nisarg.paisa.data.PaisaDb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dumps everything to JSON so it can be pulled off-device and analysed.
 *
 * Writes to the app's external files directory, which `adb pull` can read with
 * no permissions and no root, and additionally copies to Downloads so it can be
 * grabbed with a file manager if the cable is not around.
 */
object Exporter {

    private const val TAG = "PaisaExport"

    fun export(context: Context): File {
        val db = PaisaDb.get(context)
        val root = JSONObject()

        root.put("exported_at", System.currentTimeMillis())
        root.put("parser_version", dev.nisarg.paisa.parse.TxnParser.PARSER_VERSION)
        root.put("device", "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        root.put("raw_events", tableToJson(db, "raw_events"))
        root.put("parsed_txn", tableToJson(db, "parsed_txn"))
        // merchant_categories and self_identities are the only tables that cannot
        // be regenerated from raw_events — they are what the user taught the app.
        // Leaving them out of the backup made the export lossy.
        root.put("merchant_categories", tableToJson(db, "merchant_categories"))
        root.put("merchant_aliases", tableToJson(db, "merchant_aliases"))
        root.put("txn_splits", tableToJson(db, "txn_splits"))
        root.put("one_offs", tableToJson(db, "one_offs"))
        root.put("custom_categories", tableToJson(db, "custom_categories"))
        root.put("self_identities", tableToJson(db, "self_identities"))
        root.put("unmatched_packages", tableToJson(db, "unmatched_packages"))
        root.put("heartbeat", tableToJson(db, "heartbeat"))

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "paisa-export-$stamp.json"
        val payload = root.toString(2)

        val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val file = File(dir, name)
        file.writeText(payload)
        Log.i(TAG, "exported ${file.absolutePath} (${payload.length} bytes)")

        copyToDownloads(context, name, payload)
        return file
    }

    private fun tableToJson(db: PaisaDb, table: String): JSONArray {
        val arr = JSONArray()
        db.readableDatabase.rawQuery("SELECT * FROM $table ORDER BY 1", null).use { c ->
            while (c.moveToNext()) {
                val row = JSONObject()
                for (i in 0 until c.columnCount) {
                    val col = c.getColumnName(i)
                    when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> row.put(col, JSONObject.NULL)
                        android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(col, c.getLong(i))
                        android.database.Cursor.FIELD_TYPE_FLOAT -> row.put(col, c.getDouble(i))
                        else -> row.put(col, c.getString(i))
                    }
                }
                arr.put(row)
            }
        }
        return arr
    }

    private fun copyToDownloads(context: Context, name: String, payload: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "copied to Downloads/$name")
        } catch (t: Throwable) {
            // Nice-to-have. The adb-pullable copy is the one that matters.
            Log.w(TAG, "Downloads copy failed", t)
        }
    }
}
