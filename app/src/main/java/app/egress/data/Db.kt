package app.egress.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db(context: Context) : SQLiteOpenHelper(context, "egress.db", null, 2) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        SCHEMA.forEach { db.execSQL(it) }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE lookup ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0")
    }

    companion object {
        val SCHEMA = listOf(
            "CREATE TABLE app (uid INTEGER PRIMARY KEY, pkg TEXT, label TEXT NOT NULL)",
            "CREATE TABLE lookup (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, uid INTEGER NOT NULL, domain TEXT NOT NULL, ok INTEGER NOT NULL DEFAULT 1, blocked INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX lookup_ts ON lookup(ts)",
            "CREATE INDEX lookup_uid_ts ON lookup(uid, ts)",
            "CREATE TABLE answer (lookup_id INTEGER NOT NULL, ip TEXT NOT NULL, PRIMARY KEY (lookup_id, ip)) WITHOUT ROWID",
            "CREATE INDEX answer_ip ON answer(ip)",
            // ok: 1 = located, 0 = lookup failed (retried later), -1 = private / unroutable address
            "CREATE TABLE geo (ip TEXT PRIMARY KEY, ok INTEGER NOT NULL, cc TEXT, country TEXT, city TEXT, lat REAL, lon REAL, org TEXT, asn TEXT, ts INTEGER NOT NULL)",
        )
    }
}
