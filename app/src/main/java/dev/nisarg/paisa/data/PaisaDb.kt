package dev.nisarg.paisa.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.nisarg.paisa.parse.Buckets
import dev.nisarg.paisa.parse.Categoriser
import dev.nisarg.paisa.parse.Money
import dev.nisarg.paisa.parse.Reconciler
import dev.nisarg.paisa.parse.TxnParser
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Append-only capture store.
 *
 * raw_events is the source of truth and is never edited or deleted.
 * parsed_txn is derived and disposable — it can be wiped and regenerated from
 * raw_events at any time, which is the whole point of the phase.
 */
class PaisaDb(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    companion object {
        const val NAME = "paisa.db"
        const val VERSION = 12

        /**
         * Money that leaves the account but is not consumption: card-bill
         * settlements (already counted purchase by purchase) and money converted
         * into assets. Applied to every spending total so no two screens disagree.
         */
        const val NOT_A_TRANSFER =
            "(p.category IS NULL OR p.category NOT IN ('TRANSFER','INVESTMENT','RENT_SHARE'))"

        /** Notifications update themselves in place; collapse re-posts inside this window. */
        const val NOTIF_DEDUPE_WINDOW_MS = 120_000L

        @Volatile private var instance: PaisaDb? = null

        fun get(context: Context): PaisaDb =
            instance ?: synchronized(this) {
                instance ?: PaisaDb(context).also { instance = it }
            }

        /** Shared so onCreate and onUpgrade can never drift apart. */
        val PARSED_TXN_DDL = """
            CREATE TABLE IF NOT EXISTS parsed_txn (
              id              INTEGER PRIMARY KEY AUTOINCREMENT,
              raw_event_id    INTEGER NOT NULL,
              parser_version  INTEGER NOT NULL,
              direction       TEXT NOT NULL,
              amount_minor    INTEGER,
              merchant_raw    TEXT,
              instrument      TEXT NOT NULL,
              ref_id          TEXT,
              confidence      REAL NOT NULL,
              matched_rule    TEXT,
              rejected_reason TEXT,
              category        TEXT,
              category_source TEXT,
              account_tail    TEXT,
              balance_minor   INTEGER,
              balance_at      INTEGER,
              parsed_at       INTEGER NOT NULL,
              FOREIGN KEY(raw_event_id) REFERENCES raw_events(id)
            )
        """.trimIndent()

        /**
         * What the user has told us a merchant is. Survives every reparse — this
         * is the memory that makes the app stop asking.
         */
        /**
         * One payment, several purposes.
         *
         * A rent payment is the clearest case: the landlord is paid in full by
         * one person, part of it their own cost and part of it collected from
         * flatmates. Filing the whole payment either way is wrong, so it is
         * stored as parts. Keyed on raw_event_id and kept in its own table, so
         * it survives every reparse like the other things the user taught us.
         */
        val TXN_SPLITS_DDL = """
            CREATE TABLE IF NOT EXISTS txn_splits (
              raw_event_id INTEGER NOT NULL,
              ordinal      INTEGER NOT NULL,
              category     TEXT NOT NULL,
              amount_minor INTEGER NOT NULL,
              PRIMARY KEY (raw_event_id, ordinal)
            )
        """.trimIndent()

        /**
         * Nothing before 2026 is worth filing — it is too old to remember and
         * only makes the backlog look hopeless. Capture still keeps it all; this
         * only bounds what the app asks about and reports on.
         */
        fun historyFloor(): Long = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.JANUARY, 1, 0, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis

        /**
         * A human name for a payee.
         *
         * Bank SMS carries UPI handles, not shop names — "vyapar.900000000001@
         * hdfcbank" is the showroom you bought your bike from, and no amount of
         * parsing can know that. Only the user can supply it, and once supplied
         * it should never have to be supplied again.
         *
         * Keyed on the normalised merchant, the same key the learned categories
         * use, so naming and filing stay in step.
         */
        /**
         * Payees the user has confirmed are the same thing.
         *
         * Stored separately and reversibly, never applied automatically. Joining
         * two different people corrupts every figure that follows with no symptom
         * to notice, so the merge is always a decision, never an inference.
         */
        /** Budgets the user set. Categories are stored comma-separated. */
        /**
         * Summaries that were called in and not dealt with.
         *
         * Snoozing must not be dismissal. A busy evening should not quietly erase
         * the day, so the call is recorded and shown in the app until it is
         * answered.
         */
        val CALL_LOG_DDL = """
            CREATE TABLE IF NOT EXISTS call_log (
              called_at    INTEGER PRIMARY KEY,
              snoozed_till INTEGER NOT NULL,
              spent_minor  INTEGER NOT NULL,
              unfiled      INTEGER NOT NULL,
              answered     INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        val BUCKET_PLANS_DDL = """
            CREATE TABLE IF NOT EXISTS bucket_plans (
              label         TEXT PRIMARY KEY,
              categories    TEXT NOT NULL,
              amount_minor  INTEGER NOT NULL,
              period_cycles INTEGER NOT NULL DEFAULT 1,
              sort_order    INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        val MERCHANT_MERGES_DDL = """
            CREATE TABLE IF NOT EXISTS merchant_merges (
              alias_merchant TEXT PRIMARY KEY,
              canonical      TEXT NOT NULL,
              merged_at      INTEGER NOT NULL
            )
        """.trimIndent()

        val MERCHANT_ALIASES_DDL = """
            CREATE TABLE IF NOT EXISTS merchant_aliases (
              merchant_key TEXT PRIMARY KEY,
              raw_seen     TEXT,
              alias        TEXT NOT NULL,
              updated_at   INTEGER NOT NULL
            )
        """.trimIndent()

        /**
         * Payments that are real spending but not part of the rhythm — a bike, a
         * deposit, a wedding gift.
         *
         * A flag rather than a category, because the bike purchase genuinely IS
         * a vehicle expense; it is only exceptional. Making it a category would
         * force a choice between "where it belongs" and "how unusual it was",
         * and would still leave a ₹20,000 one-off drowning every ₹800 service in
         * month-on-month comparisons.
         */
        val ONE_OFFS_DDL = """
            CREATE TABLE IF NOT EXISTS one_offs (
              raw_event_id INTEGER PRIMARY KEY,
              noted_at     INTEGER NOT NULL
            )
        """.trimIndent()

        /** Categories the user typed. Free text, so the list is never a ceiling. */
        val CUSTOM_CATEGORIES_DDL = """
            CREATE TABLE IF NOT EXISTS custom_categories (
              name       TEXT PRIMARY KEY,
              created_at INTEGER NOT NULL,
              times_used INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent()

        val MERCHANT_CATEGORIES_DDL = """
            CREATE TABLE IF NOT EXISTS merchant_categories (
              merchant_key  TEXT PRIMARY KEY,
              merchant_seen TEXT,
              category      TEXT NOT NULL,
              times_used    INTEGER NOT NULL DEFAULT 1,
              updated_at    INTEGER NOT NULL
            )
        """.trimIndent()

        fun sha1(vararg parts: String?): String {
            val joined = parts.joinToString("\u0000") { it ?: "" }
            val bytes = MessageDigest.getInstance("SHA-1").digest(joined.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE raw_events (
              id            INTEGER PRIMARY KEY AUTOINCREMENT,
              source        TEXT NOT NULL,
              package_name  TEXT,
              sender        TEXT,
              title         TEXT,
              body          TEXT,
              extras_json   TEXT,
              posted_at     INTEGER NOT NULL,
              captured_at   INTEGER NOT NULL,
              notif_key     TEXT,
              dedupe_hash   TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_raw_hash ON raw_events(dedupe_hash)")
        db.execSQL("CREATE INDEX idx_raw_captured ON raw_events(captured_at)")

        db.execSQL(PARSED_TXN_DDL)

        db.execSQL("CREATE INDEX idx_parsed_raw ON parsed_txn(raw_event_id)")
        db.execSQL("CREATE INDEX idx_parsed_ref ON parsed_txn(ref_id)")

        // Package names only. Never any content. Reveals payment apps we forgot
        // to listen to — including Jupiter's package name, whenever it appears.
        db.execSQL(
            """
            CREATE TABLE unmatched_packages (
              package_name  TEXT PRIMARY KEY,
              first_seen    INTEGER NOT NULL,
              count         INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(MERCHANT_CATEGORIES_DDL)
        db.execSQL(CUSTOM_CATEGORIES_DDL)
        db.execSQL(TXN_SPLITS_DDL)
        db.execSQL(MERCHANT_ALIASES_DDL)
        db.execSQL(MERCHANT_MERGES_DDL)
        db.execSQL(BUCKET_PLANS_DDL)
        db.execSQL(CALL_LOG_DDL)
        db.execSQL(ONE_OFFS_DDL)

        // The user's own names, UPI IDs and account handles. Payments to these
        // are transfers between the user's own accounts, not spending. Kept as
        // data rather than code so identity is never baked into the parser — and
        // this is the seed of Phase 2's learned merchant memory.
        db.execSQL(
            """
            CREATE TABLE self_identities (
              value     TEXT PRIMARY KEY,
              added_at  INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // A gap here proves the listener was dead, turning silent data loss into
        // detectable data loss.
        db.execSQL(
            """
            CREATE TABLE heartbeat (
              id  INTEGER PRIMARY KEY AUTOINCREMENT,
              at  INTEGER NOT NULL,
              why TEXT
            )
            """.trimIndent()
        )
    }

    /**
     * raw_events holds captured history that cannot be re-collected — SMS that
     * have scrolled out of the inbox, notifications that fired once. Upgrades are
     * therefore strictly additive: new tables and columns only, never a rebuild.
     * parsed_txn may be dropped freely; it regenerates from raw_events.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 12) db.execSQL(CALL_LOG_DDL)
        if (oldVersion < 11) db.execSQL(BUCKET_PLANS_DDL)
        if (oldVersion < 10) db.execSQL(MERCHANT_MERGES_DDL)
        if (oldVersion < 9) {
            // parsed_txn is derived; rebuilding it is cheaper and safer than
            // ALTER TABLE, and reparse repopulates the new columns.
            db.execSQL("DROP TABLE IF EXISTS parsed_txn")
            db.execSQL(PARSED_TXN_DDL)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_parsed_raw ON parsed_txn(raw_event_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_parsed_ref ON parsed_txn(ref_id)")
        }
        if (oldVersion < 7) db.execSQL(ONE_OFFS_DDL)
        if (oldVersion < 6) db.execSQL(MERCHANT_ALIASES_DDL)
        if (oldVersion < 5) db.execSQL(TXN_SPLITS_DDL)
        if (oldVersion < 4) db.execSQL(CUSTOM_CATEGORIES_DDL)
        if (oldVersion < 3) {
            // parsed_txn is derived data — dropping and regenerating it is the
            // cheapest correct migration. raw_events is never touched.
            db.execSQL("DROP TABLE IF EXISTS parsed_txn")
            db.execSQL(PARSED_TXN_DDL)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_parsed_raw ON parsed_txn(raw_event_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_parsed_ref ON parsed_txn(ref_id)")
            db.execSQL(MERCHANT_CATEGORIES_DDL)
        }
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS self_identities (
                  value     TEXT PRIMARY KEY,
                  added_at  INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    // ---- writes -----------------------------------------------------------

    /** @return row id, or -1 if this was a duplicate we deliberately skipped. */
    fun insertRawEvent(
        source: String,
        packageName: String?,
        sender: String?,
        title: String?,
        body: String?,
        extrasJson: String?,
        postedAt: Long,
        notifKey: String?,
    ): Long {
        val now = System.currentTimeMillis()

        // SMS hashes include the timestamp so backfill is idempotent but two
        // genuinely identical payments on different days both survive.
        val hash = if (source == "sms") {
            sha1(source, sender, body, postedAt.toString())
        } else {
            sha1(source, packageName, title, body)
        }

        val db = writableDatabase

        val duplicate = if (source == "sms") {
            db.rawQuery("SELECT 1 FROM raw_events WHERE dedupe_hash = ? LIMIT 1", arrayOf(hash))
        } else {
            db.rawQuery(
                "SELECT 1 FROM raw_events WHERE dedupe_hash = ? AND captured_at > ? LIMIT 1",
                arrayOf(hash, (now - NOTIF_DEDUPE_WINDOW_MS).toString())
            )
        }
        duplicate.use { if (it.moveToFirst()) return -1L }

        val values = ContentValues().apply {
            put("source", source)
            put("package_name", packageName)
            put("sender", sender)
            put("title", title)
            put("body", body)
            put("extras_json", extrasJson)
            put("posted_at", postedAt)
            put("captured_at", now)
            put("notif_key", notifKey)
            put("dedupe_hash", hash)
        }
        val id = db.insert("raw_events", null, values)
        if (id > 0) parseAndStore(id, source, packageName, sender, title, body)
        return id
    }

    private fun storeManualParsed(rawId: Long) {
        val extras = readableDatabase.rawQuery(
            "SELECT extras_json FROM raw_events WHERE id = ?", arrayOf(rawId.toString())
        ).use { if (it.moveToFirst()) it.getString(0) else null } ?: return

        val json = runCatching { JSONObject(extras) }.getOrNull() ?: return
        val amount = json.optLong("amount_minor", 0L)
        if (amount <= 0L) return
        val category = json.optString("category").takeIf { it.isNotBlank() && it != "null" }

        writableDatabase.insert("parsed_txn", null, ContentValues().apply {
            put("raw_event_id", rawId)
            put("parser_version", TxnParser.PARSER_VERSION)
            put("direction", TxnParser.Direction.DEBIT.name)
            put("amount_minor", amount)
            put("merchant_raw", json.optString("note").takeIf { it.isNotBlank() && it != "null" })
            put("instrument", TxnParser.Instrument.UNKNOWN.name)
            put("confidence", 1.0)
            put("matched_rule", "manual")
            put("category", category)
            put("category_source", if (category != null) "manual" else null)
            put("parsed_at", System.currentTimeMillis())
        })
    }

    // Read once per reparse rather than per row — reparseAll touches thousands.
    @Volatile private var selfCache: Set<String>? = null

    fun selfIdentities(): Set<String> = selfCache ?: synchronized(this) {
        selfCache ?: buildSet {
            readableDatabase.rawQuery("SELECT value FROM self_identities", null).use { c ->
                while (c.moveToNext()) add(c.getString(0))
            }
        }.also { selfCache = it }
    }

    fun addSelfIdentity(value: String) {
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO self_identities(value, added_at) VALUES(?, ?)",
            arrayOf(value.trim(), System.currentTimeMillis())
        )
        selfCache = null
    }

    @Volatile private var learnedCache: Map<String, String>? = null

    fun learnedCategories(): Map<String, String> = learnedCache ?: synchronized(this) {
        learnedCache ?: buildMap {
            readableDatabase.rawQuery(
                "SELECT merchant_key, category FROM merchant_categories", null
            ).use { c -> while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }.also { learnedCache = it }
    }

    /**
     * Teach the app what a merchant is. One correction covers every past and
     * future transaction with that merchant, because reparse replays it.
     */
    fun learnCategory(merchant: String, category: String) {
        writableDatabase.execSQL(
            """
            INSERT INTO merchant_categories(merchant_key, merchant_seen, category, times_used, updated_at)
            VALUES(?, ?, ?, 1, ?)
            ON CONFLICT(merchant_key) DO UPDATE SET
              category = excluded.category,
              merchant_seen = excluded.merchant_seen,
              times_used = times_used + 1,
              updated_at = excluded.updated_at
            """.trimIndent(),
            arrayOf(Categoriser.merchantKey(merchant), merchant, category,
                System.currentTimeMillis())
        )
        learnedCache = null
    }

    /**
     * Removes a captured row and its derived parse. Only for manual entries made
     * in error or for test rows — real captures are append-only by design.
     */
    fun deleteRawEvent(id: Long): Int {
        if (id <= 0) return 0
        val db = writableDatabase
        db.delete("parsed_txn", "raw_event_id = ?", arrayOf(id.toString()))
        return db.delete("raw_events", "id = ?", arrayOf(id.toString()))
    }

    fun removeSelfIdentity(value: String) {
        writableDatabase.delete("self_identities", "value = ?", arrayOf(value.trim()))
        selfCache = null
    }

    private fun parseAndStore(
        rawId: Long,
        source: String,
        packageName: String?,
        sender: String?,
        title: String?,
        body: String?,
    ) {
        // Manual rows carry their values instead of text to parse, so they are
        // rebuilt from extras_json rather than run through the parser.
        if (source == "manual") {
            storeManualParsed(rawId)
            return
        }

        val result = TxnParser.parse(
            TxnParser.Input(source, packageName, sender, title, body, selfIdentities())
        )

        // A bank record for an amount the user already entered retires the manual one.
        if (result.rejectedReason == null &&
            result.direction == TxnParser.Direction.DEBIT &&
            result.amountMinor != null
        ) {
            readableDatabase.rawQuery(
                "SELECT posted_at FROM raw_events WHERE id = ?", arrayOf(rawId.toString())
            ).use { c ->
                if (c.moveToFirst()) reconcileManual(result.amountMinor, c.getLong(0))
            }
        }
        // Learned beats rules, always. The user's correction is the ground truth.
        val merchant = canonical(result.merchantRaw)
        val learned = merchant?.let { learnedCategories()[Categoriser.merchantKey(it)] }
        val category = learned ?: Categoriser.byRule(merchant)?.name
        val categorySource = when {
            learned != null -> "learned"
            category != null -> "rule"
            else -> null
        }

        // A split payment is stored as one parsed row per part, so every total,
        // breakdown and percentage sees the parts rather than the lump.
        val splits = splitsFor(rawId)
        if (splits.isNotEmpty()) {
            splits.forEach { part ->
                writableDatabase.insert("parsed_txn", null, ContentValues().apply {
                    put("raw_event_id", rawId)
                    put("parser_version", result.parserVersion)
                    put("direction", result.direction.name)
                    put("amount_minor", part.amountMinor)
                    put("merchant_raw", merchant)
                    put("instrument", result.instrument.name)
                    put("ref_id", result.refId)
                    put("confidence", result.confidence)
                    put("matched_rule", (result.matchedRules + "split").joinToString(","))
                    put("rejected_reason", result.rejectedReason)
                    put("category", part.category)
                    put("category_source", "split")
                    put("parsed_at", System.currentTimeMillis())
                })
            }
            return
        }

        writableDatabase.insert("parsed_txn", null, ContentValues().apply {
            put("category", category)
            put("category_source", categorySource)
            put("raw_event_id", rawId)
            put("parser_version", result.parserVersion)
            put("direction", result.direction.name)
            if (result.amountMinor != null) put("amount_minor", result.amountMinor)
            put("merchant_raw", merchant)
            put("instrument", result.instrument.name)
            put("ref_id", result.refId)
            put("confidence", result.confidence)
            put("matched_rule", result.matchedRules.joinToString(","))
            put("rejected_reason", result.rejectedReason)
            put("account_tail", result.accountTail)
            if (result.balanceMinor != null) put("balance_minor", result.balanceMinor)
            if (result.balanceAtMillis != null) put("balance_at", result.balanceAtMillis)
            put("parsed_at", System.currentTimeMillis())
        })
    }

    /**
     * Wipe parsed_txn and re-derive it from raw_events with the current parser.
     * This is the payoff of storing raw first: a parser fix can be replayed over
     * the entire capture history without collecting anything again.
     */
    fun reparseAll(): Int {
        val db = writableDatabase
        db.beginTransaction()
        var n = 0
        selfCache = null
        learnedCache = null
        mergeCache = null
        try {
            db.delete("parsed_txn", null, null)
            db.rawQuery(
                "SELECT id, source, package_name, sender, title, body FROM raw_events ORDER BY id",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    parseAndStore(
                        c.getLong(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getString(4), c.getString(5)
                    )
                    n++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    fun noteUnmatchedPackage(packageName: String) {
        writableDatabase.execSQL(
            """
            INSERT INTO unmatched_packages(package_name, first_seen, count)
            VALUES(?, ?, 1)
            ON CONFLICT(package_name) DO UPDATE SET count = count + 1
            """.trimIndent(),
            arrayOf(packageName, System.currentTimeMillis())
        )
    }

    fun beat(why: String) {
        writableDatabase.insert("heartbeat", null, ContentValues().apply {
            put("at", System.currentTimeMillis())
            put("why", why)
        })
    }

    // ---- reads ------------------------------------------------------------

    fun count(table: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun countWhere(table: String, where: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /** Human-readable recent capture list for the debug screen. */
    fun recentSummaries(limit: Int = 40): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            """
            SELECT r.id, r.source, r.package_name, r.sender, r.title, r.body,
                   p.direction, p.amount_minor, p.merchant_raw, p.confidence, p.rejected_reason
            FROM raw_events r LEFT JOIN parsed_txn p ON p.raw_event_id = r.id
            ORDER BY r.id DESC LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val src = c.getString(1)
                val from = c.getString(2) ?: c.getString(3) ?: "?"
                val raw = (c.getString(4) ?: "") + " " + (c.getString(5) ?: "")
                val dir = c.getString(6) ?: "-"
                val amt = if (c.isNull(7)) "—" else
                    dev.nisarg.paisa.parse.Money.format(c.getLong(7))
                val merchant = c.getString(8) ?: "—"
                val conf = if (c.isNull(9)) 0.0 else c.getDouble(9)
                val rejected = c.getString(10)

                val verdict = if (rejected != null) "REJECTED($rejected)"
                else "$dir $amt → $merchant  conf=%.2f".format(conf)

                out += "#$id [$src] ${from.substringAfterLast('.')}\n  $verdict\n  ${raw.trim().take(140)}"
            }
        }
        return out
    }

    // ---- what the modal needs ---------------------------------------------

    data class Pending(
        val parsedId: Long,
        val amountMinor: Long,
        val merchant: String?,
        val postedAt: Long,
        val ruleGuess: String?,
    )

    /**
     * The spend the user most likely just made: newest uncategorised debit inside
     * the window. The window is what makes a single back-tap unambiguous — if you
     * tap right after paying, there is exactly one candidate.
     */
    fun pendingSpend(windowMs: Long = 30 * 60 * 1000L): Pending? {
        val since = System.currentTimeMillis() - windowMs
        readableDatabase.rawQuery(
            """
            SELECT p.id, p.amount_minor, p.merchant_raw, r.posted_at, p.category
            FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE p.rejected_reason IS NULL
              AND p.direction = 'DEBIT'
              AND p.amount_minor IS NOT NULL
              AND (p.category IS NULL OR p.category_source = 'rule')
              AND r.posted_at >= ?
            ORDER BY r.posted_at DESC LIMIT 1
            """.trimIndent(),
            arrayOf(since.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            return Pending(
                parsedId = c.getLong(0),
                amountMinor = c.getLong(1),
                merchant = c.getString(2),
                postedAt = c.getLong(3),
                ruleGuess = c.getString(4),
            )
        }
    }

    /**
     * A spend the user typed in themselves.
     *
     * Needed because outgoing GPay posts no notification and the bank SMS can be
     * minutes late — or, below the bank's alert threshold, never arrive at all.
     * Stored as a real raw_event so it survives reparse like everything else; the
     * amount and category ride in extras_json because there is no text to parse.
     */
    fun addManualSpend(amountMinor: Long, category: String?, note: String?): Long {
        val payload = JSONObject().apply {
            put("amount_minor", amountMinor)
            put("category", category ?: JSONObject.NULL)
            put("note", note ?: JSONObject.NULL)
        }.toString()

        val now = System.currentTimeMillis()
        val db = writableDatabase
        val id = db.insert("raw_events", null, ContentValues().apply {
            put("source", "manual")
            put("body", "Manual entry ${Money.format(amountMinor)}${note?.let { " — $it" } ?: ""}")
            put("extras_json", payload)
            put("posted_at", now)
            put("captured_at", now)
            put("dedupe_hash", sha1("manual", now.toString(), amountMinor.toString()))
        })
        if (id > 0) parseAndStore(id, "manual", null, null, null, null)
        return id
    }

    /**
     * When a real bank SMS lands for a spend the user already typed in, keep the
     * bank's row (it has the payee and reference) and retire the manual one.
     *
     * Matched on exact amount inside a window, because that is the only field both
     * records reliably share. Superseding rather than deleting keeps the manual
     * entry visible in raw_events and the decision reversible.
     */
    fun reconcileManual(amountMinor: Long, postedAt: Long, windowMs: Long = 20 * 60 * 1000L) {
        writableDatabase.execSQL(
            """
            UPDATE parsed_txn SET rejected_reason = 'superseded_by_bank'
            WHERE rejected_reason IS NULL
              AND matched_rule = 'manual'
              AND amount_minor = ?
              AND raw_event_id IN (
                SELECT id FROM raw_events
                WHERE source = 'manual' AND ABS(posted_at - ?) <= ?
              )
            """.trimIndent(),
            arrayOf(amountMinor.toString(), postedAt.toString(), windowMs.toString())
        )
    }

    /** Applies the choice to this row and remembers it for every future one. */
    fun confirmCategory(parsedId: Long, merchant: String?, category: String) {
        writableDatabase.execSQL(
            "UPDATE parsed_txn SET category = ?, category_source = 'learned' WHERE id = ?",
            arrayOf(category, parsedId.toString())
        )
        if (!merchant.isNullOrBlank()) learnCategory(merchant, category)
    }

    /** Categories the user actually uses, most-used first — the modal ranks by this. */
    fun favouriteCategories(): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT category, SUM(times_used) t FROM merchant_categories " +
                "GROUP BY category ORDER BY t DESC", null
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    fun spentSince(sinceMs: Long): Long =
        readableDatabase.rawQuery(
            """
            SELECT COALESCE(SUM(p.amount_minor), 0)
            FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE p.rejected_reason IS NULL AND p.direction = 'DEBIT'
              AND $NOT_A_TRANSFER AND r.posted_at >= ?
            """.trimIndent(),
            arrayOf(sinceMs.toString())
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    fun countSince(sinceMs: Long): Int =
        readableDatabase.rawQuery(
            """
            SELECT COUNT(*)
            FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE p.rejected_reason IS NULL AND p.direction = 'DEBIT'
              AND $NOT_A_TRANSFER AND r.posted_at >= ?
            """.trimIndent(),
            arrayOf(sinceMs.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ---- what the home screen needs ---------------------------------------

    data class Txn(
        val parsedId: Long,
        val amountMinor: Long,
        val merchant: String?,
        val category: String?,
        val at: Long,
    )

    fun spentBetween(from: Long, to: Long): Long =
        readableDatabase.rawQuery(
            """
            SELECT COALESCE(SUM(p.amount_minor), 0)
            FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE p.rejected_reason IS NULL AND p.direction = 'DEBIT'
              AND $NOT_A_TRANSFER
              AND r.posted_at >= ? AND r.posted_at < ?
            """.trimIndent(),
            arrayOf(from.toString(), to.toString())
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** Category totals for a window, biggest first. Null category means unfiled. */
    fun categoryTotals(from: Long, to: Long): List<Pair<String?, Long>> {
        val out = mutableListOf<Pair<String?, Long>>()
        readableDatabase.rawQuery(
            """
            SELECT p.category, SUM(p.amount_minor) t
            FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE p.rejected_reason IS NULL AND p.direction = 'DEBIT'
              AND p.amount_minor IS NOT NULL
              AND $NOT_A_TRANSFER
              AND r.posted_at >= ? AND r.posted_at < ?
            GROUP BY p.category ORDER BY t DESC
            """.trimIndent(),
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) out += (if (c.isNull(0)) null else c.getString(0)) to c.getLong(1)
        }
        return out
    }

    fun recentTransactions(limit: Int = 20): List<Txn> {
        val out = mutableListOf<Txn>()
        readableDatabase.rawQuery(
            """
            SELECT p.id, p.amount_minor, p.merchant_raw, p.category, r.posted_at
            FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE p.rejected_reason IS NULL AND p.direction = 'DEBIT'
              AND p.amount_minor IS NOT NULL
            ORDER BY r.posted_at DESC LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += Txn(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4))
            }
        }
        return out
    }

    /**
     * Money that left the account but is not consumption, shown separately so the
     * exclusion is visible rather than silent — a total that quietly drops ₹26k
     * is not more trustworthy than one that overstates it.
     */
    fun nonSpendTotals(from: Long, to: Long): List<Pair<String, Long>> {
        val out = mutableListOf<Pair<String, Long>>()
        readableDatabase.rawQuery(
            """
            SELECT p.category, SUM(p.amount_minor) t
            FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE p.rejected_reason IS NULL AND p.direction = 'DEBIT'
              AND p.amount_minor IS NOT NULL
              AND p.category IN ('TRANSFER','INVESTMENT')
              AND r.posted_at >= ? AND r.posted_at < ?
            GROUP BY p.category ORDER BY t DESC
            """.trimIndent(),
            arrayOf(from.toString(), to.toString())
        ).use { c -> while (c.moveToNext()) out += c.getString(0) to c.getLong(1) }
        return out
    }

    // ---- rapid filing ------------------------------------------------------

    data class UnfiledMerchant(
        val merchant: String,
        val totalMinor: Long,
        val count: Int,
        val ruleGuess: String?,
    )

    /**
     * Unfiled spend grouped by merchant, biggest money first.
     *
     * Filing per transaction is hopeless at 751 rows. Filing per *merchant* is
     * not: the same payees repeat, so one tap can settle dozens of rows, and
     * ordering by value means the backlog's rupee total collapses fastest even
     * if you stop after a minute.
     */
    fun unfiledMerchants(limit: Int = 60): List<UnfiledMerchant> {
        val out = mutableListOf<UnfiledMerchant>()
        readableDatabase.rawQuery(
            """
            SELECT p.merchant_raw, SUM(p.amount_minor) t, COUNT(*) n
            FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE p.rejected_reason IS NULL AND p.direction = 'DEBIT'
              AND p.amount_minor IS NOT NULL
              AND p.merchant_raw IS NOT NULL AND TRIM(p.merchant_raw) <> ''
              AND p.category IS NULL
              AND r.posted_at >= ?
            GROUP BY p.merchant_raw ORDER BY t DESC LIMIT ?
            """.trimIndent(),
            arrayOf(historyFloor().toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                out += UnfiledMerchant(
                    name, c.getLong(1), c.getInt(2),
                    Categoriser.byRule(name)?.name
                )
            }
        }
        return out
    }

    /** Total still unfiled, so the user can see the pile shrink. */
    fun unfiledTotal(): Long =
        readableDatabase.rawQuery(
            """
            SELECT COALESCE(SUM(p.amount_minor), 0)
            FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE p.rejected_reason IS NULL AND p.direction = 'DEBIT'
              AND p.amount_minor IS NOT NULL AND p.category IS NULL
              AND r.posted_at >= ?
            """.trimIndent(), arrayOf(historyFloor().toString())
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /**
     * Files every unfiled row for a merchant at once and remembers the choice,
     * so the answer applies to past rows and to everything that merchant does
     * from now on. @return how many rows this settled.
     */
    fun fileMerchant(merchant: String, category: String): Int {
        val db = writableDatabase
        val n = db.update(
            "parsed_txn",
            ContentValues().apply {
                put("category", category)
                put("category_source", "learned")
            },
            "rejected_reason IS NULL AND direction = 'DEBIT' AND category IS NULL AND merchant_raw = ?",
            arrayOf(merchant)
        )
        learnCategory(merchant, category)
        return n
    }

    // ---- categories the user invents ---------------------------------------

    fun customCategories(): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT name FROM custom_categories ORDER BY times_used DESC, name ASC", null
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    /**
     * Stored upper-case so "Rent", "rent" and "RENT" cannot become three
     * different categories that each hold part of the answer.
     */
    fun addCustomCategory(rawName: String): String? {
        val name = rawName.trim().uppercase().replace(Regex("\\s+"), " ")
        if (name.isBlank() || name.length > 24) return null
        // Never shadow a built-in: that would split one category across two keys.
        if (runCatching { Categoriser.Category.valueOf(name.replace(" ", "_")) }.isSuccess) {
            return name.replace(" ", "_")
        }
        writableDatabase.execSQL(
            "INSERT INTO custom_categories(name, created_at, times_used) VALUES(?, ?, 0) " +
                "ON CONFLICT(name) DO UPDATE SET times_used = times_used + 1",
            arrayOf(name, System.currentTimeMillis())
        )
        return name
    }

    // ---- one payment, several purposes -------------------------------------

    data class Split(val category: String, val amountMinor: Long)

    data class Payment(
        val rawEventId: Long,
        val amountMinor: Long,
        val at: Long,
        val merchant: String?,
        val body: String?,
        val refId: String?,
        val instrument: String?,
        val splits: List<Split>,
    )

    /**
     * The individual payments behind a merchant, newest first, with the original
     * message text. Filing from a name and a total alone asks the user to
     * remember an amount out of context; the date, time and raw text are what
     * actually jog a memory.
     */
    fun paymentsFor(merchant: String, limit: Int = 25): List<Payment> {
        val out = mutableListOf<Payment>()
        readableDatabase.rawQuery(
            """
            SELECT r.id, p.amount_minor, r.posted_at, p.merchant_raw, r.body,
                   p.ref_id, p.instrument
            FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE p.rejected_reason IS NULL AND p.direction = 'DEBIT'
              AND p.amount_minor IS NOT NULL AND p.merchant_raw = ?
              AND r.posted_at >= ?
            ORDER BY r.posted_at DESC LIMIT ?
            """.trimIndent(),
            arrayOf(merchant, historyFloor().toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out += Payment(id, c.getLong(1), c.getLong(2), c.getString(3),
                    c.getString(4), c.getString(5), c.getString(6), splitsFor(id))
            }
        }
        return out
    }

    fun paymentById(rawEventId: Long): Payment? {
        readableDatabase.rawQuery(
            """
            SELECT r.id, p.amount_minor, r.posted_at, p.merchant_raw, r.body,
                   p.ref_id, p.instrument
            FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE r.id = ? AND p.direction = 'DEBIT' AND p.amount_minor IS NOT NULL
            ORDER BY p.id LIMIT 1
            """.trimIndent(),
            arrayOf(rawEventId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            // A payment that is already split has several parsed rows; the
            // original amount is the sum of its parts, not the first row.
            val existing = splitsFor(rawEventId)
            val total = if (existing.isEmpty()) c.getLong(1) else existing.sumOf { it.amountMinor }
            return Payment(c.getLong(0), total, c.getLong(2), c.getString(3),
                c.getString(4), c.getString(5), c.getString(6), existing)
        }
    }

    fun splitsFor(rawEventId: Long): List<Split> {
        val out = mutableListOf<Split>()
        readableDatabase.rawQuery(
            "SELECT category, amount_minor FROM txn_splits WHERE raw_event_id = ? ORDER BY ordinal",
            arrayOf(rawEventId.toString())
        ).use { c -> while (c.moveToNext()) out += Split(c.getString(0), c.getLong(1)) }
        return out
    }

    /**
     * Replaces any existing split for a payment and regenerates its parsed rows,
     * so the breakdown reflects the parts immediately.
     */
    fun saveSplit(rawEventId: Long, parts: List<Split>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("txn_splits", "raw_event_id = ?", arrayOf(rawEventId.toString()))
            parts.forEachIndexed { i, part ->
                db.insert("txn_splits", null, ContentValues().apply {
                    put("raw_event_id", rawEventId)
                    put("ordinal", i)
                    put("category", part.category)
                    put("amount_minor", part.amountMinor)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        reparseOne(rawEventId)
    }

    fun clearSplit(rawEventId: Long) {
        writableDatabase.delete("txn_splits", "raw_event_id = ?", arrayOf(rawEventId.toString()))
        reparseOne(rawEventId)
    }

    private fun reparseOne(rawEventId: Long) {
        val db = writableDatabase
        db.delete("parsed_txn", "raw_event_id = ?", arrayOf(rawEventId.toString()))
        db.rawQuery(
            "SELECT source, package_name, sender, title, body FROM raw_events WHERE id = ?",
            arrayOf(rawEventId.toString())
        ).use { c ->
            if (c.moveToFirst()) parseAndStore(
                rawEventId, c.getString(0), c.getString(1),
                c.getString(2), c.getString(3), c.getString(4)
            )
        }
    }

    // ---- naming a payee ----------------------------------------------------

    @Volatile private var aliasCache: Map<String, String>? = null

    fun aliases(): Map<String, String> = aliasCache ?: synchronized(this) {
        aliasCache ?: buildMap {
            readableDatabase.rawQuery(
                "SELECT merchant_key, alias FROM merchant_aliases", null
            ).use { c -> while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }.also { aliasCache = it }
    }

    /** What to show a human. Falls back to the raw payee when unnamed. */
    fun displayName(merchant: String?): String {
        if (merchant.isNullOrBlank()) return "Unknown payee"
        return aliases()[Categoriser.merchantKey(merchant)] ?: merchant
    }

    fun setAlias(merchant: String, alias: String) {
        val clean = alias.trim()
        if (clean.isBlank()) return
        writableDatabase.execSQL(
            """
            INSERT INTO merchant_aliases(merchant_key, raw_seen, alias, updated_at)
            VALUES(?, ?, ?, ?)
            ON CONFLICT(merchant_key) DO UPDATE SET
              alias = excluded.alias, updated_at = excluded.updated_at
            """.trimIndent(),
            arrayOf(Categoriser.merchantKey(merchant), merchant, clean,
                System.currentTimeMillis())
        )
        aliasCache = null
    }

    fun clearAlias(merchant: String) {
        writableDatabase.delete("merchant_aliases", "merchant_key = ?",
            arrayOf(Categoriser.merchantKey(merchant)))
        aliasCache = null
    }

    // ---- one-offs ----------------------------------------------------------

    fun isOneOff(rawEventId: Long): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM one_offs WHERE raw_event_id = ?", arrayOf(rawEventId.toString())
        ).use { it.moveToFirst() }

    fun setOneOff(rawEventId: Long, on: Boolean) {
        if (on) writableDatabase.execSQL(
            "INSERT OR REPLACE INTO one_offs(raw_event_id, noted_at) VALUES(?, ?)",
            arrayOf(rawEventId, System.currentTimeMillis())
        ) else writableDatabase.delete(
            "one_offs", "raw_event_id = ?", arrayOf(rawEventId.toString()))
    }

    /**
     * The exceptional slice of a window. Reported alongside the total rather than
     * removed from it — the money was really spent, it just should not be read as
     * a trend.
     */
    fun oneOffTotal(from: Long, to: Long): Long =
        readableDatabase.rawQuery(
            """
            SELECT COALESCE(SUM(p.amount_minor), 0)
            FROM parsed_txn p
              JOIN raw_events r ON r.id = p.raw_event_id
              JOIN one_offs o ON o.raw_event_id = r.id
            WHERE p.rejected_reason IS NULL AND p.direction = 'DEBIT'
              AND $NOT_A_TRANSFER
              AND r.posted_at >= ? AND r.posted_at < ?
            """.trimIndent(),
            arrayOf(from.toString(), to.toString())
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /**
     * Files small historical payments as OTHER in one go.
     *
     * The distribution justifies it: 108 payments under ₹100 came to ₹3,880 —
     * two per cent of the backlog. Recalling a ₹40 payment from March buys
     * nothing, and leaving it unfiled makes the pile look like work.
     *
     * Bounded to BEFORE the current cycle on purpose. This month is the month
     * being watched, and flattening it into OTHER would destroy exactly the
     * detail that is worth having.
     *
     * Deliberately does NOT teach merchant memory: a ₹40 payment is no reason to
     * mark a shop as OTHER forever. The next payment to that payee asks again.
     *
     * @return how many payments this settled.
     */
    fun settleSmallBefore(maxAmountMinor: Long, beforeMs: Long): Int =
        writableDatabase.compileStatement(
            """
            UPDATE parsed_txn SET category = 'OTHER', category_source = 'bulk'
            WHERE rejected_reason IS NULL AND direction = 'DEBIT'
              AND category IS NULL AND amount_minor IS NOT NULL
              AND amount_minor <= ?
              AND raw_event_id IN (
                SELECT id FROM raw_events WHERE posted_at >= ? AND posted_at < ?
              )
            """.trimIndent()
        ).use { st ->
            st.bindLong(1, maxAmountMinor)
            st.bindLong(2, historyFloor())
            st.bindLong(3, beforeMs)
            st.executeUpdateDelete()
        }

    /** How many payments a settle would touch, so the user is told before it runs. */
    fun countSmallBefore(maxAmountMinor: Long, beforeMs: Long): Pair<Int, Long> {
        readableDatabase.rawQuery(
            """
            SELECT COUNT(*), COALESCE(SUM(p.amount_minor), 0)
            FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE p.rejected_reason IS NULL AND p.direction = 'DEBIT'
              AND p.category IS NULL AND p.amount_minor IS NOT NULL
              AND p.amount_minor <= ?
              AND r.posted_at >= ? AND r.posted_at < ?
            """.trimIndent(),
            arrayOf(maxAmountMinor.toString(), historyFloor().toString(), beforeMs.toString())
        ).use { return if (it.moveToFirst()) it.getInt(0) to it.getLong(1) else 0 to 0L }
    }

    /**
     * Files every unfiled row for a payee as OTHER without learning it — the user
     * looked and could not place it. A future payment to the same payee asks
     * again, when they might remember.
     */
    fun giveUpOn(merchant: String): Int =
        writableDatabase.update(
            "parsed_txn",
            ContentValues().apply {
                put("category", Categoriser.Category.OTHER.name)
                put("category_source", "forgotten")
            },
            "rejected_reason IS NULL AND direction = 'DEBIT' AND category IS NULL AND merchant_raw = ?",
            arrayOf(merchant)
        )

    // ---- reconciliation ----------------------------------------------------

    /** Accounts that quote a balance often enough to be worth checking. */
    fun reconcilableAccounts(minReadings: Int = 4): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            """
            SELECT account_tail, COUNT(*) n FROM parsed_txn
            WHERE rejected_reason IS NULL AND account_tail IS NOT NULL
              AND balance_minor IS NOT NULL
            GROUP BY account_tail HAVING n >= ? ORDER BY n DESC
            """.trimIndent(), arrayOf(minReadings.toString())
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    fun readingsFor(accountTail: String, from: Long, to: Long): List<Reconciler.Reading> {
        val out = mutableListOf<Reconciler.Reading>()
        readableDatabase.rawQuery(
            """
            SELECT COALESCE(p.balance_at, r.posted_at) AS at,
                   p.balance_minor, p.direction, p.amount_minor
            FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE p.rejected_reason IS NULL
              AND p.account_tail = ? AND p.balance_minor IS NOT NULL
              AND r.posted_at >= ? AND r.posted_at < ?
            ORDER BY at ASC
            """.trimIndent(),
            arrayOf(accountTail, from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val dir = runCatching { TxnParser.Direction.valueOf(c.getString(2)) }
                    .getOrDefault(TxnParser.Direction.UNKNOWN)
                val amt = if (c.isNull(3)) null else c.getLong(3)
                out += Reconciler.Reading(c.getLong(0), c.getLong(1),
                    Reconciler.movementOf(dir, amt))
            }
        }
        return out
    }

    /**
     * True when this payment is the first ever to that payee.
     *
     * Memory for a payee decays fast — a Rs.14,240 payment was unidentifiable
     * three months later, and would have been named instantly on the day. Asking
     * at the moment of the first payment costs two seconds and prevents every
     * future "what was this?".
     */
    fun isFirstTimePayee(merchant: String?): Boolean {
        if (merchant.isNullOrBlank()) return false
        // Already named means already asked.
        if (aliases().containsKey(Categoriser.merchantKey(merchant))) return false
        return readableDatabase.rawQuery(
            """
            SELECT COUNT(*) FROM parsed_txn
            WHERE rejected_reason IS NULL AND direction = 'DEBIT' AND merchant_raw = ?
            """.trimIndent(), arrayOf(merchant)
        ).use { (if (it.moveToFirst()) it.getInt(0) else 0) <= 1 }
    }

    /**
     * A payee worth naming: an opaque handle rather than something already
     * readable. "Shree Rasoi Kathiyawadi" needs no help; "q11223344@ybl" does.
     */
    fun looksOpaque(merchant: String?): Boolean {
        if (merchant.isNullOrBlank()) return false
        if (merchant.contains("@")) return true
        val digits = merchant.count { it.isDigit() }
        return digits >= 4 || merchant.length <= 4
    }

    /**
     * Category totals for each COMPLETED pay cycle, newest first.
     *
     * The current cycle is excluded deliberately: a part-finished cycle would
     * drag every baseline down and make ordinary spending look excessive.
     */
    fun completedCycleTotals(maxCycles: Int = 8): List<Map<String, Long>> {
        val paydays = salaryHistory(maxCycles + 1).map { it.at }.sorted()
        if (paydays.size < 2) return emptyList()
        val out = mutableListOf<Map<String, Long>>()
        // Consecutive paydays bound a completed cycle; the last payday starts the
        // cycle currently running, so it is not a boundary we can close.
        for ((from, to) in paydays.zipWithNext()) {
            out += categoryTotals(from, to).mapNotNull { (c, v) -> c?.let { it to v } }.toMap()
        }
        return out.reversed()
    }

    // ---- merged identities -------------------------------------------------

    @Volatile private var mergeCache: Map<String, String>? = null

    fun merges(): Map<String, String> = mergeCache ?: synchronized(this) {
        mergeCache ?: buildMap {
            readableDatabase.rawQuery(
                "SELECT alias_merchant, canonical FROM merchant_merges", null
            ).use { c -> while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }.also { mergeCache = it }
    }

    /** Resolves a payee to whatever it has been merged into. */
    fun canonical(merchant: String?): String? =
        if (merchant == null) null else merges()[merchant] ?: merchant

    fun mergePayees(alias: String, canonical: String) {
        if (alias == canonical) return
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO merchant_merges(alias_merchant, canonical, merged_at) " +
                "VALUES(?, ?, ?)",
            arrayOf(alias, canonical, System.currentTimeMillis())
        )
        mergeCache = null
        // Existing rows carry the old payee, so rewrite them to match.
        writableDatabase.execSQL(
            "UPDATE parsed_txn SET merchant_raw = ? WHERE merchant_raw = ?",
            arrayOf(canonical, alias)
        )
    }

    fun unmergePayee(alias: String) {
        writableDatabase.delete("merchant_merges", "alias_merchant = ?", arrayOf(alias))
        mergeCache = null
    }

    /** Distinct payees seen, for the matcher to look over. */
    fun allPayees(limit: Int = 3000): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            """
            SELECT DISTINCT merchant_raw FROM parsed_txn
            WHERE rejected_reason IS NULL AND merchant_raw IS NOT NULL
              AND TRIM(merchant_raw) <> '' LIMIT ?
            """.trimIndent(), arrayOf(limit.toString())
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    // ---- budgets the user set ----------------------------------------------

    fun bucketPlans(): List<Buckets.Plan> {
        val out = mutableListOf<Buckets.Plan>()
        readableDatabase.rawQuery(
            "SELECT label, categories, amount_minor, period_cycles FROM bucket_plans " +
                "ORDER BY sort_order, amount_minor DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                out += Buckets.Plan(
                    label = c.getString(0),
                    categories = c.getString(1).split(",").map { it.trim() }
                        .filter { it.isNotEmpty() }.toSet(),
                    amountMinor = c.getLong(2),
                    periodCycles = c.getInt(3).coerceAtLeast(1),
                )
            }
        }
        return out
    }

    fun setBucketPlan(plan: Buckets.Plan, order: Int = 0) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO bucket_plans(label, categories, amount_minor, " +
                "period_cycles, sort_order) VALUES(?, ?, ?, ?, ?)",
            arrayOf(plan.label, plan.categories.joinToString(","),
                plan.amountMinor, plan.periodCycles, order)
        )
    }

    fun deleteBucketPlan(label: String) {
        writableDatabase.delete("bucket_plans", "label = ?", arrayOf(label))
    }

    /**
     * Start of the window a bucket is judged over: the current cycle for a
     * monthly bucket, or N cycles back for one that spans several.
     */
    fun periodStart(periodCycles: Int): Long {
        val paydays = salaryHistory(periodCycles + 2).map { it.at }.sortedDescending()
        if (paydays.isEmpty()) return System.currentTimeMillis() - 30L * 86_400_000
        return paydays.getOrNull(periodCycles - 1) ?: paydays.last()
    }

    // ---- the call log ------------------------------------------------------

    data class MissedCall(
        val calledAt: Long,
        val spentMinor: Long,
        val unfiled: Int,
        val snoozedTill: Long,
    )

    fun logCall(spentMinor: Long, unfiled: Int) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO call_log(called_at, snoozed_till, spent_minor, unfiled, answered) " +
                "VALUES(?, 0, ?, ?, 0)",
            arrayOf(System.currentTimeMillis(), spentMinor, unfiled)
        )
    }

    /** @param till when to call back, or 0 for declined without a callback. */
    fun snooze(till: Long) {
        writableDatabase.execSQL(
            "UPDATE call_log SET snoozed_till = ? WHERE answered = 0 AND called_at = " +
                "(SELECT MAX(called_at) FROM call_log)",
            arrayOf(till)
        )
    }

    fun clearSnooze() {
        writableDatabase.execSQL(
            "UPDATE call_log SET answered = 1 WHERE answered = 0")
    }

    /** Calls that were never answered, newest first. */
    fun missedCalls(limit: Int = 14): List<MissedCall> {
        val out = mutableListOf<MissedCall>()
        readableDatabase.rawQuery(
            "SELECT called_at, spent_minor, unfiled, snoozed_till FROM call_log " +
                "WHERE answered = 0 ORDER BY called_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += MissedCall(c.getLong(0), c.getLong(1), c.getInt(2), c.getLong(3))
            }
        }
        return out
    }

    /** The day's largest single payment: payee and amount. */
    fun biggestToday(from: Long, to: Long): Pair<String?, Long>? {
        readableDatabase.rawQuery(
            """
            SELECT p.merchant_raw, p.amount_minor
            FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE p.rejected_reason IS NULL AND p.direction = 'DEBIT'
              AND p.amount_minor IS NOT NULL AND $NOT_A_TRANSFER
              AND r.posted_at >= ? AND r.posted_at < ?
            ORDER BY p.amount_minor DESC LIMIT 1
            """.trimIndent(),
            arrayOf(from.toString(), to.toString())
        ).use { return if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null }
    }

    fun uncategorisedCount(): Int =
        readableDatabase.rawQuery(
            """
            SELECT COUNT(*) FROM parsed_txn
            WHERE rejected_reason IS NULL AND direction = 'DEBIT'
              AND amount_minor IS NOT NULL AND category IS NULL
            """.trimIndent(), null
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ---- the salary cycle --------------------------------------------------

    data class Salary(val at: Long, val amountMinor: Long, val label: String?)

    /**
     * The most recent salary credit.
     *
     * People do not budget by calendar month, they budget from payday to payday.
     * The bank says so explicitly — "deposited in HDFC Bank A/c XX9021 ... -Salary
     * -SALARY" — so the cycle can be anchored to the real event instead of to the
     * 1st of the month, which is an accident of the calendar.
     */
    fun lastSalary(): Salary? {
        readableDatabase.rawQuery(
            """
            SELECT r.posted_at, p.amount_minor, r.body
            FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE p.rejected_reason IS NULL
              AND p.direction = 'CREDIT'
              AND p.amount_minor IS NOT NULL
              AND LOWER(r.body) LIKE '%salary%'
            ORDER BY r.posted_at DESC LIMIT 1
            """.trimIndent(), null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return Salary(c.getLong(0), c.getLong(1), c.getString(2))
        }
    }

    /** Every salary credit, newest first — used to learn the usual gap. */
    fun salaryHistory(limit: Int = 8): List<Salary> {
        val out = mutableListOf<Salary>()
        readableDatabase.rawQuery(
            """
            SELECT r.posted_at, p.amount_minor, r.body
            FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE p.rejected_reason IS NULL AND p.direction = 'CREDIT'
              AND p.amount_minor IS NOT NULL AND LOWER(r.body) LIKE '%salary%'
            ORDER BY r.posted_at DESC LIMIT ?
            """.trimIndent(), arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out += Salary(c.getLong(0), c.getLong(1), c.getString(2))
        }
        return out
    }

    /** Daily spend totals in a window — the burn-down curve. */
    fun dailyTotals(from: Long, to: Long): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        readableDatabase.rawQuery(
            """
            SELECT r.posted_at, p.amount_minor
            FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE p.rejected_reason IS NULL AND p.direction = 'DEBIT'
              AND p.amount_minor IS NOT NULL
              AND r.posted_at >= ? AND r.posted_at < ?
            ORDER BY r.posted_at ASC
            """.trimIndent(),
            arrayOf(from.toString(), to.toString())
        ).use { c -> while (c.moveToNext()) out += c.getLong(0) to c.getLong(1) }
        return out
    }

    fun topMerchants(from: Long, to: Long, limit: Int = 6): List<Triple<String, Long, Int>> {
        val out = mutableListOf<Triple<String, Long, Int>>()
        readableDatabase.rawQuery(
            """
            SELECT p.merchant_raw, SUM(p.amount_minor) t, COUNT(*) n
            FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE p.rejected_reason IS NULL AND p.direction = 'DEBIT'
              AND p.amount_minor IS NOT NULL AND p.merchant_raw IS NOT NULL
              AND $NOT_A_TRANSFER
              AND r.posted_at >= ? AND r.posted_at < ?
            GROUP BY p.merchant_raw ORDER BY t DESC LIMIT ?
            """.trimIndent(),
            arrayOf(from.toString(), to.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) out += Triple(c.getString(0), c.getLong(1), c.getInt(2))
        }
        return out
    }

    fun countBetween(from: Long, to: Long): Int =
        readableDatabase.rawQuery(
            """
            SELECT COUNT(*) FROM parsed_txn p JOIN raw_events r ON r.id = p.raw_event_id
            WHERE p.rejected_reason IS NULL AND p.direction = 'DEBIT'
              AND $NOT_A_TRANSFER
              AND r.posted_at >= ? AND r.posted_at < ?
            """.trimIndent(),
            arrayOf(from.toString(), to.toString())
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun unmatchedPackages(): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT package_name, count FROM unmatched_packages ORDER BY count DESC LIMIT 60", null
        ).use { c -> while (c.moveToNext()) out += "${c.getString(0)}  ×${c.getInt(1)}" }
        return out
    }
}
