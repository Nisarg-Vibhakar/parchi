package dev.nisarg.paisa.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.nisarg.paisa.capture.SmsBackfill
import dev.nisarg.paisa.data.PaisaDb
import dev.nisarg.paisa.export.Exporter
import dev.nisarg.paisa.work.Heartbeat

/**
 * The entire Phase 1 UI. One screen: is it working, what has it caught, get the
 * data out. No categories, no modal, no home screen — those get designed against
 * real captures, not against guesses.
 */
class DebugActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var recent: TextView
    private lateinit var unmatched: TextView

    private val db by lazy { PaisaDb.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        Heartbeat.schedule(this)
        db.beat("app_open")
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---- ui ---------------------------------------------------------------

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(header("Paisa — Phase 1 capture"))

        status = mono()
        col.addView(status)

        col.addView(button("1. Grant notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        col.addView(button("2. Grant SMS permission") {
            requestPermissions(
                arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS), 1
            )
        })

        col.addView(button("3. Disable battery optimisation") {
            openBatterySettings()
        })

        col.addView(button("Backfill SMS inbox") {
            background("Backfilling…") {
                val s = SmsBackfill.run(this)
                "Scanned ${s.scanned}, imported ${s.imported}, skipped ${s.skipped}"
            }
        })

        col.addView(button("Re-parse everything") {
            background("Re-parsing…") { "Re-parsed ${db.reparseAll()} events" }
        })

        col.addView(button("EXPORT") {
            background("Exporting…") { "Wrote ${Exporter.export(this).absolutePath}" }
        })

        col.addView(header("Recent captures"))
        recent = mono()
        col.addView(recent)

        col.addView(header("Unmatched packages (names only)"))
        unmatched = mono()
        col.addView(unmatched)

        return ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            addView(col)
        }
    }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(0, 24, 0, 8)
    }

    private fun mono() = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = 11f
        setTextIsSelectable(true)
        setPadding(0, 4, 0, 12)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    // ---- state -------------------------------------------------------------

    private fun refresh() {
        val notifOk = hasNotificationAccess()
        val smsOk = checkSelfPermission(Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val batteryOk = isIgnoringBatteryOptimisations()

        fun mark(ok: Boolean) = if (ok) "OK " else "NO "

        status.text = buildString {
            appendLine("${mark(notifOk)}  notification access")
            appendLine("${mark(smsOk)}  sms permission")
            appendLine("${mark(batteryOk)}  battery optimisation disabled")
            appendLine()
            appendLine("raw events     ${db.count("raw_events")}")
            appendLine("  notifications ${db.countWhere("raw_events", "source='notification'")}")
            appendLine("  sms           ${db.countWhere("raw_events", "source='sms'")}")
            appendLine("parsed         ${db.count("parsed_txn")}")
            appendLine("  rejected      ${db.countWhere("parsed_txn", "rejected_reason IS NOT NULL")}")
            appendLine("  confident     ${db.countWhere("parsed_txn", "confidence >= 0.75")}")
            appendLine("  no amount     ${db.countWhere("parsed_txn", "amount_minor IS NULL AND rejected_reason IS NULL")}")
            appendLine("heartbeats     ${db.count("heartbeat")}")
        }
        status.setTextColor(if (notifOk && smsOk) Color.DKGRAY else Color.RED)

        recent.text = db.recentSummaries().joinToString("\n\n").ifEmpty { "nothing captured yet" }
        unmatched.text = db.unmatchedPackages().joinToString("\n").ifEmpty { "none seen yet" }
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return enabled != null && enabled.contains(packageName)
    }

    private fun isIgnoringBatteryOptimisations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (t: Throwable) {
            // Some Motorola builds block the direct request; fall back to the list.
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        Toast.makeText(
            this,
            "Also set Settings > Apps > Paisa > Battery to 'Unrestricted'",
            Toast.LENGTH_LONG
        ).show()
    }

    // ---- helpers -----------------------------------------------------------

    private fun background(busy: String, work: () -> String) {
        Toast.makeText(this, busy, Toast.LENGTH_SHORT).show()
        Thread {
            val message = try {
                work()
            } catch (t: Throwable) {
                "Failed: ${t.message}"
            }
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }
}
