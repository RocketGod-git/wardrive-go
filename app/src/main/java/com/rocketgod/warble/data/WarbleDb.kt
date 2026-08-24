package com.rocketgod.warble.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "observations",
    indices = [
        Index("type"), Index("category"), Index("maker"), Index("run_id"),
        Index(value = ["in_wigle", "type"]), Index(value = ["in_wdgw", "type"]),

        Index(value = ["lat", "lng"])
    ]
)
data class ObservationEntity(
    @PrimaryKey val key: String,
    val type: String,
    val name: String?,
    val maker: String?,
    val category: String,
    val icon: String,
    @ColumnInfo(name = "best_rssi") val bestRssi: Int,
    @ColumnInfo(name = "last_rssi") val lastRssi: Int,
    @ColumnInfo(name = "times_seen") val timesSeen: Int,
    @ColumnInfo(name = "first_seen") val firstSeen: Long,
    @ColumnInfo(name = "last_seen") val lastSeen: Long,
    @ColumnInfo(name = "company_id") val companyId: Int?,
    val channel: Int?,
    val frequency: Int?,
    val capabilities: String?,
    val connectable: Boolean,
    val lat: Double?,
    val lng: Double?,
    val altitude: Double?,
    val accuracy: Double?,
    @ColumnInfo(name = "run_id") val runId: Long,
    @ColumnInfo(name = "in_wigle") val inWigle: Boolean,
    @ColumnInfo(name = "exported_at") val exportedAt: Long? = null,
    @ColumnInfo(name = "via_monitor") val viaMonitor: Boolean = false,
    @ColumnInfo(name = "in_wdgw") val inWdgw: Boolean = false,
    @ColumnInfo(name = "wifi_standard") val wifiStandard: Int = 0,

    @ColumnInfo(name = "notable", defaultValue = "0") val notable: Boolean = false
)

@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "contact_count") val contactCount: Long
)

@Entity(tableName = "export_sessions")
data class ExportSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "exported_at") val exportedAt: Long,
    val total: Int,
    val ble: Int,
    val wifi: Int,
    val cell: Int,
    val dest: String = ""
)

@Entity(tableName = "wigle_known")
data class WigleKnownEntity(@PrimaryKey val bssid: String)

@Entity(tableName = "gnss_sat")
data class GnssSatEntity(
    @PrimaryKey val id: String,
    val constellation: String,
    val svid: Int,
    @ColumnInfo(name = "best_cn0") val bestCn0: Float,
    @ColumnInfo(name = "last_cn0") val lastCn0: Float,
    val elevation: Float,
    val azimuth: Float,
    @ColumnInfo(name = "used_in_fix") val usedInFix: Boolean,
    @ColumnInfo(name = "has_almanac") val hasAlmanac: Boolean,
    @ColumnInfo(name = "has_ephemeris") val hasEphemeris: Boolean,
    @ColumnInfo(name = "carrier_hz") val carrierHz: Float,
    val health: Int?,
    @ColumnInfo(name = "ura_index") val uraIndex: Int?,
    @ColumnInfo(name = "sv_config") val svConfig: Int?,
    @ColumnInfo(name = "anti_spoof") val antiSpoof: Boolean?,
    @ColumnInfo(name = "first_seen") val firstSeen: Long,
    @ColumnInfo(name = "last_seen") val lastSeen: Long,
    @ColumnInfo(name = "times_seen") val timesSeen: Long
)

@Entity(tableName = "cell_tower")
data class CellTowerEntity(
    @PrimaryKey val id: String,
    val tech: String,
    val operator: String,
    val mcc: String,
    val mnc: String,
    val cid: Long,
    val pci: Int?,
    val tac: Int?,
    val arfcn: Int?,
    val band: String?,
    @ColumnInfo(name = "best_dbm") val bestDbm: Int,
    @ColumnInfo(name = "last_dbm") val lastDbm: Int,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    @ColumnInfo(name = "timing_advance") val timingAdvance: Int?,
    @ColumnInfo(name = "registered_ever") val registeredEver: Boolean,
    @ColumnInfo(name = "first_seen") val firstSeen: Long,
    @ColumnInfo(name = "last_seen") val lastSeen: Long,
    @ColumnInfo(name = "times_seen") val timesSeen: Long
)

@Entity(tableName = "pmkids")
data class PmkidEntity(
    @PrimaryKey val pmkid: String,
    val bssid: String,
    val sta: String,
    val ssid: String?,
    val channel: Int,
    val rssi: Int,
    val lat: Double?,
    val lng: Double?,
    @ColumnInfo(name = "first_seen") val firstSeen: Long,
    val kind: Int = 1,
    val hashline: String? = null
)

@Entity(tableName = "privacy_zones")
data class PrivacyZone(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    @ColumnInfo(name = "radius_m") val radiusM: Double,
    val label: String,
    val enabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

@Entity(tableName = "blocked_devices")
data class BlockedDevice(
    @PrimaryKey val bssid: String,
    val label: String?,
    @ColumnInfo(name = "added_at") val addedAt: Long
)

data class TypeAgg(
    val type: String,
    val unique: Long,
    val observations: Long,
    val newThisRun: Long,
    val inWigle: Long
)

data class MakerAgg(val maker: String, val devices: Long)

data class CategoryCount(val category: String, val c: Int)

data class DashTypeRow(
    val type: String,
    @ColumnInfo(name = "unique_c") val unique: Long,
    val observations: Long,
    val newThisRun: Long,
    val inWigle: Long,
    val notWigle: Long,
    val notWdgw: Long
)

@Dao
interface WarbleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(o: ObservationEntity)

    @Transaction
    suspend fun upsertBatch(rows: List<ObservationEntity>) { for (r in rows) upsert(r) }

    @Query("SELECT * FROM observations WHERE key = :key LIMIT 1")
    suspend fun find(key: String): ObservationEntity?

    @Query("SELECT COUNT(*) FROM observations")
    fun lifetimeUnique(): Flow<Long>

    @Query("SELECT COUNT(*) FROM observations WHERE run_id = :runId")
    fun runUnique(runId: Long): Flow<Long>

    @Query("SELECT COUNT(*) FROM observations WHERE category = :cat")
    fun countCategory(cat: String): Flow<Long>

    @Query(
        "SELECT type AS type, COUNT(*) AS unique_c, " +
            "COALESCE(SUM(times_seen),0) AS observations, " +
            "COALESCE(SUM(CASE WHEN run_id = :runId THEN 1 ELSE 0 END),0) AS newThisRun, " +
            "COALESCE(SUM(CASE WHEN in_wigle = 1 THEN 1 ELSE 0 END),0) AS inWigle " +
            "FROM observations GROUP BY type"
    )
    fun typeAggregates(runId: Long): Flow<List<TypeAggRow>>

    @Query(
        "SELECT COALESCE(NULLIF(maker,''),'Unknown') AS maker, COUNT(*) AS devices " +
            "FROM observations GROUP BY maker ORDER BY maker COLLATE NOCASE ASC"
    )
    fun makerBreakdown(): Flow<List<MakerAggRow>>

    @Query("SELECT * FROM observations ORDER BY name COLLATE NOCASE ASC")
    fun allSorted(): Flow<List<ObservationEntity>>

    @Query("SELECT category, COUNT(*) AS c FROM observations GROUP BY category")
    fun categoryCounts(): Flow<List<CategoryCount>>

    @Query(
        "SELECT type AS type, COUNT(*) AS unique_c, " +
            "COALESCE(SUM(times_seen),0) AS observations, " +
            "COALESCE(SUM(CASE WHEN run_id = :rid THEN 1 ELSE 0 END),0) AS newThisRun, " +
            "COALESCE(SUM(CASE WHEN in_wigle = 1 THEN 1 ELSE 0 END),0) AS inWigle, " +

            "COALESCE(SUM(CASE WHEN in_wigle = 0 AND lat IS NOT NULL AND lng IS NOT NULL AND NOT (type = 'WIFI' AND lower(substr(key, 2, 1)) IN ('2','3','6','7','a','b','e','f')) AND category != 'WiFi Client' THEN 1 ELSE 0 END),0) AS notWigle, " +
            "COALESCE(SUM(CASE WHEN in_wdgw = 0 AND lat IS NOT NULL AND lng IS NOT NULL AND NOT (type = 'WIFI' AND lower(substr(key, 2, 1)) IN ('2','3','6','7','a','b','e','f')) AND category != 'WiFi Client' THEN 1 ELSE 0 END),0) AS notWdgw " +
            "FROM observations GROUP BY type"
    )
    suspend fun dashByType(rid: Long): List<DashTypeRow>

    @Query("SELECT category, COUNT(*) AS c FROM observations GROUP BY category")
    suspend fun categoryCountsNow(): List<CategoryCount>

    @Query("SELECT COALESCE(MAX(contact_count),0) FROM runs")
    suspend fun bestRunNow(): Long

    @Query("SELECT COUNT(*) FROM runs WHERE ended_at IS NOT NULL")
    suspend fun finishedRunCountNow(): Long

    @Query("SELECT COALESCE(MAX(c),0) FROM (SELECT COUNT(*) c FROM observations WHERE run_id != 0 AND type = :type GROUP BY run_id)")
    suspend fun bestRunForTypeNow(type: String): Long

    @Query("SELECT * FROM observations WHERE run_id = :runId")
    suspend fun observationsForRun(runId: Long): List<ObservationEntity>

    @Query("SELECT * FROM observations")
    suspend fun allObservations(): List<ObservationEntity>

    @Insert
    suspend fun insertRun(r: RunEntity): Long

    @Query("SELECT * FROM runs WHERE ended_at IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun openRun(): RunEntity?

    @Query("SELECT COUNT(*) FROM runs WHERE ended_at IS NOT NULL")
    fun finishedRunCount(): Flow<Long>

    @Query("SELECT COUNT(DISTINCT run_id) FROM observations WHERE run_id != 0")
    fun runCountDistinct(): Flow<Long>

    @Query("SELECT COALESCE(MAX(contact_count),0) FROM runs")
    fun bestRun(): Flow<Long>

    @Query("UPDATE runs SET ended_at = :ts, contact_count = :count WHERE id = :id")
    suspend fun closeRun(id: Long, ts: Long, count: Long)

    @Query("SELECT COUNT(*) FROM observations WHERE run_id = :runId")
    suspend fun runCount(runId: Long): Long

    @Query("SELECT * FROM observations WHERE exported_at IS NULL")
    suspend fun notExported(): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE key > :afterKey ORDER BY key LIMIT :n")
    suspend fun allObservationsAfter(afterKey: String, n: Int): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE exported_at IS NULL AND key > :afterKey ORDER BY key LIMIT :n")
    suspend fun notExportedAfter(afterKey: String, n: Int): List<ObservationEntity>

    @Query("UPDATE observations SET exported_at = :ts WHERE key IN (:keys)")
    suspend fun markExported(keys: List<String>, ts: Long)

    @Query("SELECT * FROM observations WHERE in_wigle = 0")
    suspend fun notInWigle(): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE in_wigle = 0 ORDER BY last_seen DESC LIMIT :n")
    suspend fun sampleNotInWigle(n: Int): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE in_wigle = 0 AND key > :afterKey ORDER BY key LIMIT :n")
    suspend fun notInWigleAfter(afterKey: String, n: Int): List<ObservationEntity>

    @Query("SELECT COUNT(*) FROM observations WHERE in_wigle = 0 AND lat IS NOT NULL AND lng IS NOT NULL AND NOT (type = 'WIFI' AND lower(substr(key, 2, 1)) IN ('2','3','6','7','a','b','e','f')) AND category != 'WiFi Client'")
    fun countNotInWigle(): Flow<Long>

    @Query("SELECT COUNT(*) FROM observations WHERE in_wigle = 0 AND type = 'WIFI' AND lat IS NOT NULL AND lng IS NOT NULL AND lower(substr(key, 2, 1)) NOT IN ('2','3','6','7','a','b','e','f') AND category != 'WiFi Client'")
    fun countNotInWigleWifi(): Flow<Long>

    @Query("SELECT COUNT(*) FROM observations WHERE in_wigle = 0 AND type = :type AND lat IS NOT NULL AND lng IS NOT NULL AND NOT (type = 'WIFI' AND lower(substr(key, 2, 1)) IN ('2','3','6','7','a','b','e','f')) AND category != 'WiFi Client'")
    fun countNotInWigleType(type: String): Flow<Long>

    @Query("UPDATE observations SET in_wigle = 1 WHERE key IN (:keys)")
    suspend fun markInWigle(keys: List<String>): Int

    @Query("SELECT COUNT(*) FROM observations WHERE in_wigle = 0 AND lat IS NOT NULL AND lng IS NOT NULL AND NOT (type = 'WIFI' AND lower(substr(key, 2, 1)) IN ('2','3','6','7','a','b','e','f')) AND category != 'WiFi Client'")
    suspend fun countNotInWigleNow(): Long

    @Query("SELECT COUNT(*) FROM observations WHERE in_wigle = 0 AND NOT (type = 'WIFI' AND lower(substr(key, 2, 1)) IN ('2','3','6','7','a','b','e','f')) AND category != 'WiFi Client'")
    suspend fun countNotInWigleAnyLocNow(): Long

    @Query("SELECT COUNT(*) FROM observations WHERE in_wdgw = 0 AND lat IS NOT NULL AND lng IS NOT NULL AND NOT (type = 'WIFI' AND lower(substr(key, 2, 1)) IN ('2','3','6','7','a','b','e','f')) AND category != 'WiFi Client'")
    suspend fun countNotInWdgwNow(): Long

    @Query("SELECT COUNT(*) FROM observations WHERE in_wdgw = 0 AND NOT (type = 'WIFI' AND lower(substr(key, 2, 1)) IN ('2','3','6','7','a','b','e','f')) AND category != 'WiFi Client'")
    suspend fun countNotInWdgwAnyLocNow(): Long

    @Query("SELECT * FROM observations WHERE in_wdgw = 0")
    suspend fun notInWdgw(): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE in_wdgw = 0 AND key > :afterKey ORDER BY key LIMIT :n")
    suspend fun notInWdgwAfter(afterKey: String, n: Int): List<ObservationEntity>

    @Query("SELECT COUNT(*) FROM observations WHERE in_wdgw = 0 AND lat IS NOT NULL AND lng IS NOT NULL AND NOT (type = 'WIFI' AND lower(substr(key, 2, 1)) IN ('2','3','6','7','a','b','e','f')) AND category != 'WiFi Client'")
    fun countNotInWdgw(): Flow<Long>

    @Query("SELECT COUNT(*) FROM observations WHERE in_wdgw = 0 AND type = 'WIFI' AND lat IS NOT NULL AND lng IS NOT NULL AND lower(substr(key, 2, 1)) NOT IN ('2','3','6','7','a','b','e','f') AND category != 'WiFi Client'")
    fun countNotInWdgwWifi(): Flow<Long>

    @Query("UPDATE observations SET in_wdgw = 1 WHERE key IN (:keys)")
    suspend fun markInWdgw(keys: List<String>): Int

    @Query("SELECT COALESCE(MAX(c),0) FROM (SELECT COUNT(*) c FROM observations WHERE run_id != 0 AND type = :type GROUP BY run_id)")
    fun bestRunForType(type: String): Flow<Long>

    @Query("SELECT COALESCE(MAX(c),0) FROM (SELECT COUNT(*) c FROM observations WHERE run_id != 0 GROUP BY run_id)")
    fun bestRunNewAll(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKnown(rows: List<WigleKnownEntity>)

    @Query("SELECT COUNT(*) FROM wigle_known")
    suspend fun knownCount(): Long

    @Query("SELECT EXISTS(SELECT 1 FROM wigle_known WHERE bssid = :bssid)")
    suspend fun isWigleKnown(bssid: String): Boolean

    @Query("UPDATE observations SET in_wigle = 1 WHERE type = 'WIFI' AND in_wigle = 0 AND key IN (SELECT bssid FROM wigle_known)")
    suspend fun markKnownWifiInWigle(): Int

    @Insert
    suspend fun insertExportSession(s: ExportSessionEntity): Long

    @Query("SELECT * FROM export_sessions ORDER BY exported_at DESC")
    fun exportSessions(): Flow<List<ExportSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGnssSat(s: GnssSatEntity)

    @Query("SELECT * FROM gnss_sat WHERE id = :id")
    suspend fun gnssSat(id: String): GnssSatEntity?

    @Query("SELECT * FROM gnss_sat ORDER BY constellation, svid")
    fun gnssSats(): Flow<List<GnssSatEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCellTower(t: CellTowerEntity)

    @Query("SELECT * FROM cell_tower WHERE id = :id")
    suspend fun cellTower(id: String): CellTowerEntity?

    @Query("SELECT * FROM cell_tower ORDER BY registered_ever DESC, best_dbm DESC")
    fun cellTowers(): Flow<List<CellTowerEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPmkid(p: PmkidEntity)

    @Query("SELECT * FROM pmkids WHERE pmkid = :id")
    suspend fun pmkid(id: String): PmkidEntity?

    @Query("SELECT * FROM pmkids ORDER BY first_seen DESC")
    fun pmkids(): Flow<List<PmkidEntity>>

    @Query("SELECT * FROM pmkids ORDER BY first_seen DESC")
    suspend fun pmkidList(): List<PmkidEntity>

    @Query("SELECT COUNT(*) FROM pmkids WHERE first_seen >= :since")
    suspend fun captureCountSince(since: Long): Long

    @Query("SELECT key, type, name, lat, lng FROM observations WHERE lat IS NOT NULL AND lng IS NOT NULL LIMIT :max")
    suspend fun mapPoints(max: Int): List<MapPtRow>

    @Query("SELECT key, type, name, lat, lng FROM observations WHERE lat >= :south AND lat <= :north AND lng >= :west AND lng <= :east LIMIT :max")
    suspend fun mapPointsIn(south: Double, north: Double, west: Double, east: Double, max: Int): List<MapPtRow>

    @Query("SELECT key, type, name, company_id AS companyId, category, lat, lng FROM observations WHERE lat >= :south AND lat <= :north AND lng >= :west AND lng <= :east LIMIT :max")
    suspend fun classifyRowsIn(south: Double, north: Double, west: Double, east: Double, max: Int): List<ClassifyRow>

    @Query("SELECT key, type, name, company_id AS companyId, category, lat, lng FROM observations WHERE notable = 1 AND lat >= :south AND lat <= :north AND lng >= :west AND lng <= :east LIMIT :max")
    suspend fun notableRowsIn(south: Double, north: Double, west: Double, east: Double, max: Int): List<ClassifyRow>

    @Query("SELECT lat, lng FROM observations WHERE lat IS NOT NULL AND lng IS NOT NULL ORDER BY last_seen DESC LIMIT 1")
    suspend fun recentLocated(): LatLngRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertZone(z: PrivacyZone): Long

    @Query("SELECT * FROM privacy_zones ORDER BY created_at DESC")
    fun zones(): Flow<List<PrivacyZone>>

    @Query("SELECT * FROM privacy_zones")
    suspend fun zonesNow(): List<PrivacyZone>

    @Query("UPDATE privacy_zones SET enabled = :on WHERE id = :id")
    suspend fun setZoneEnabled(id: Long, on: Boolean)

    @Query("DELETE FROM privacy_zones WHERE id = :id")
    suspend fun deleteZone(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBlocked(b: BlockedDevice)

    @Query("SELECT * FROM blocked_devices ORDER BY added_at DESC")
    fun blockedDevices(): Flow<List<BlockedDevice>>

    @Query("SELECT * FROM blocked_devices")
    suspend fun blockedDevicesNow(): List<BlockedDevice>

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_devices WHERE bssid = :bssid)")
    fun isBlockedFlow(bssid: String): Flow<Boolean>

    @Query("DELETE FROM blocked_devices WHERE bssid = :bssid")
    suspend fun deleteBlocked(bssid: String)

    @Query("SELECT key, name, lat, lng FROM observations")
    suspend fun exclusionCandidates(): List<ExclCandidate>

    @Query("SELECT key, name, lat, lng FROM observations WHERE key > :afterKey ORDER BY key LIMIT :n")
    suspend fun exclusionCandidatesPaged(afterKey: String, n: Int): List<ExclCandidate>

    @Query("SELECT type AS type, category AS category, COUNT(*) AS c, " +
        "COALESCE(SUM(CASE WHEN lat IS NOT NULL AND lng IS NOT NULL THEN 1 ELSE 0 END),0) AS located " +
        "FROM observations GROUP BY type, category")
    fun typeCatCounts(): Flow<List<TypeCatRow>>

    @Query("SELECT category AS category, COUNT(*) AS c FROM observations WHERE via_monitor = 1 GROUP BY category")
    fun monitorCatCounts(): Flow<List<CategoryCount>>

    @Query("SELECT * FROM observations WHERE type = :type AND category = :cat ORDER BY best_rssi DESC LIMIT :limit")
    suspend fun obsByCat(type: String, cat: String, limit: Int): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE via_monitor = 1 AND category = :cat ORDER BY best_rssi DESC LIMIT :limit")
    suspend fun obsMonitorByCat(cat: String, limit: Int): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE maker IN (:brands) ORDER BY best_rssi DESC LIMIT :limit")
    suspend fun obsByMakers(brands: List<String>, limit: Int): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE maker = :maker ORDER BY best_rssi DESC LIMIT :limit")
    suspend fun obsByMaker(maker: String, limit: Int): List<ObservationEntity>

    @Query("SELECT * FROM observations WHERE maker IS NULL OR maker = '' ORDER BY best_rssi DESC LIMIT :limit")
    suspend fun obsUnknownMaker(limit: Int): List<ObservationEntity>

    @Query("SELECT key, name, company_id AS companyId, category, maker, lat, lng FROM observations WHERE type = :type AND lat IS NOT NULL AND lng IS NOT NULL LIMIT :max")
    suspend fun mapPointsByType(type: String, max: Int): List<LabeledPtRow>

    @Query("SELECT key, name, company_id AS companyId, category, maker, lat, lng FROM observations WHERE maker IN (:brands) AND lat IS NOT NULL AND lng IS NOT NULL LIMIT :max")
    suspend fun mapPointsByMakers(brands: List<String>, max: Int): List<LabeledPtRow>
}

data class ExclCandidate(val key: String, val name: String?, val lat: Double?, val lng: Double?)

data class MapPtRow(val key: String, val type: String, val name: String?, val lat: Double, val lng: Double)

data class LabeledPtRow(val key: String, val name: String?, val companyId: Int?, val category: String, val maker: String?, val lat: Double, val lng: Double)

data class ClassifyRow(val key: String, val type: String, val name: String?, val companyId: Int?, val category: String, val lat: Double, val lng: Double)

data class LatLngRow(val lat: Double, val lng: Double)

data class TypeCatRow(val type: String, val category: String, val c: Int, val located: Int)

data class TypeAggRow(
    val type: String,
    @ColumnInfo(name = "unique_c") val unique: Long,
    val observations: Long,
    val newThisRun: Long,
    val inWigle: Long
)

data class MakerAggRow(val maker: String, val devices: Long)

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE observations ADD COLUMN via_monitor INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE observations ADD COLUMN in_wdgw INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS wigle_known (bssid TEXT NOT NULL PRIMARY KEY)")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS gnss_sat (" +
                "id TEXT NOT NULL PRIMARY KEY, constellation TEXT NOT NULL, svid INTEGER NOT NULL, " +
                "best_cn0 REAL NOT NULL, last_cn0 REAL NOT NULL, elevation REAL NOT NULL, " +
                "azimuth REAL NOT NULL, used_in_fix INTEGER NOT NULL, has_almanac INTEGER NOT NULL, " +
                "has_ephemeris INTEGER NOT NULL, carrier_hz REAL NOT NULL, health INTEGER, " +
                "ura_index INTEGER, sv_config INTEGER, anti_spoof INTEGER, " +
                "first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, times_seen INTEGER NOT NULL)"
        )
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE export_sessions ADD COLUMN dest TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE observations ADD COLUMN wifi_standard INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS pmkids (" +
                "pmkid TEXT NOT NULL PRIMARY KEY, bssid TEXT NOT NULL, sta TEXT NOT NULL, ssid TEXT, " +
                "channel INTEGER NOT NULL, rssi INTEGER NOT NULL, lat REAL, lng REAL, " +
                "first_seen INTEGER NOT NULL)"
        )
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pmkids ADD COLUMN kind INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE pmkids ADD COLUMN hashline TEXT")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_observations_type ON observations (type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_observations_category ON observations (category)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_observations_maker ON observations (maker)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_observations_run_id ON observations (run_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_observations_in_wigle_type ON observations (in_wigle, type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_observations_in_wdgw_type ON observations (in_wdgw, type)")
    }
}

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE observations ADD COLUMN notable INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "UPDATE observations SET notable = 1 WHERE " +
                "substr(lower(key),1,8) = 'b4:1e:52' " +
                "OR lower(name) LIKE 'flock%' OR lower(name) LIKE 'penguin%' " +
                "OR lower(name) LIKE '%pigvision%' OR lower(name) LIKE '%fs ext battery%' " +
                "OR lower(name) LIKE '%flipper%' OR lower(name) LIKE '%pineapple%' " +
                "OR lower(name) LIKE '%pwnagotchi%' OR lower(name) LIKE '%pandwarf%' " +
                "OR lower(name) LIKE '%usbkill%' OR lower(name) LIKE '%rogue pro%' " +
                "OR lower(category) LIKE '%camera%' OR lower(category) LIKE '%drone%' " +
                "OR lower(category) LIKE '%glasses%' OR lower(category) LIKE '%tracker%' " +
                "OR lower(category) LIKE '%body cam%' OR lower(category) LIKE '%alpr%' " +
                "OR lower(category) LIKE '%flipper%' OR lower(category) LIKE '%rf tool%'"
        )
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {

        db.execSQL("CREATE INDEX IF NOT EXISTS index_observations_lat_lng ON observations (lat, lng)")
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS privacy_zones (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, " +
                "radius_m REAL NOT NULL, label TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, " +
                "created_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS blocked_devices (" +
                "bssid TEXT NOT NULL PRIMARY KEY, label TEXT, added_at INTEGER NOT NULL)"
        )
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS cell_tower (" +
                "id TEXT NOT NULL PRIMARY KEY, tech TEXT NOT NULL, operator TEXT NOT NULL, " +
                "mcc TEXT NOT NULL, mnc TEXT NOT NULL, cid INTEGER NOT NULL, pci INTEGER, tac INTEGER, " +
                "arfcn INTEGER, band TEXT, best_dbm INTEGER NOT NULL, last_dbm INTEGER NOT NULL, " +
                "rsrp INTEGER, rsrq INTEGER, sinr INTEGER, timing_advance INTEGER, " +
                "registered_ever INTEGER NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, " +
                "times_seen INTEGER NOT NULL)"
        )
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE observations ADD COLUMN exported_at INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS export_sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "exported_at INTEGER NOT NULL, total INTEGER NOT NULL, " +
                "ble INTEGER NOT NULL, wifi INTEGER NOT NULL, cell INTEGER NOT NULL)"
        )
    }
}

@Database(entities = [ObservationEntity::class, RunEntity::class, ExportSessionEntity::class, WigleKnownEntity::class, GnssSatEntity::class, CellTowerEntity::class, PmkidEntity::class, PrivacyZone::class, BlockedDevice::class], version = 15, exportSchema = false)
abstract class WarbleDb : RoomDatabase() {
    abstract fun dao(): WarbleDao

    companion object {
        @Volatile private var INSTANCE: WarbleDb? = null
        fun get(context: Context): WarbleDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }

        private fun create(ctx: Context): WarbleDb =
            Room.databaseBuilder(ctx, WarbleDb::class.java, "warble.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
                .fallbackToDestructiveMigration()
                .build()

        private fun build(ctx: Context): WarbleDb {
            return try {
                val db = create(ctx)

                db.openHelper.writableDatabase.query("SELECT count(*) FROM sqlite_master").use { it.moveToFirst() }
                db
            } catch (e: android.database.sqlite.SQLiteException) {

                try { ctx.deleteDatabase("warble.db") } catch (_: Throwable) {}
                create(ctx)
            }
        }
    }
}
