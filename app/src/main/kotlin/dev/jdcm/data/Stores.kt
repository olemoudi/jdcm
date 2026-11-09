package dev.jdcm.data

import android.content.Context
import dev.jdcm.core.model.GroupSnapshot
import dev.jdcm.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

val StoreJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/**
 * One JSON file, written atomically and durably: a temp file synced to disk, the previous version
 * kept as `.bak`, then a rename. A crash mid-write leaves the previous version, never half a file.
 * A file that does not parse is set aside (`.bad`, logged) and the `.bak` is read instead, so a
 * torn write can never turn "two children" into "no children".
 */
class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>) {
    private val lock = Mutex()
    private val bak get() = File(file.parentFile, file.name + ".bak")

    suspend fun read(): T? = withContext(Dispatchers.IO) {
        lock.withLock {
            parse(file)?.let { return@withLock it }
            if (file.exists()) {
                DiagLog.w("Store", "unreadable ${file.name}; set aside, trying the backup")
                runCatching { file.renameTo(File(file.parentFile, file.name + ".bad")) }
            }
            parse(bak)?.also { DiagLog.w("Store", "${file.name} restored from its backup") }
        }
    }

    private fun parse(f: File): T? {
        if (!f.exists()) return null
        return runCatching { StoreJson.decodeFromString(serializer, f.readText()) }
            .onFailure { DiagLog.w("Store", "cannot parse ${f.name}: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    suspend fun write(value: T) = withContext(Dispatchers.IO) {
        lock.withLock {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(StoreJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (file.exists()) {
                bak.delete()
                if (!file.renameTo(bak)) file.copyTo(bak, overwrite = true)
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    suspend fun delete() = withContext(Dispatchers.IO) { lock.withLock { file.delete(); bak.delete() } }
}

/** Every group the app has looked at, on disk and in memory. Screens collect [snapshots]. */
class GroupCache(context: Context) {
    private val dir = File(context.filesDir, "groups")
    private val files = HashMap<Int, JsonFile<GroupSnapshot>>()
    private val loaded = HashSet<Int>()
    private val mutable = MutableStateFlow<Map<Int, GroupSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<Int, GroupSnapshot>> = mutable.asStateFlow()

    private fun file(groupId: Int) = synchronized(files) {
        files.getOrPut(groupId) { JsonFile(File(dir, "$groupId.json"), GroupSnapshot.serializer()) }
    }

    suspend fun get(groupId: Int): GroupSnapshot? {
        mutable.value[groupId]?.let { return it }
        if (synchronized(loaded) { groupId in loaded }) return null
        val s = file(groupId).read()
        synchronized(loaded) { loaded += groupId }
        if (s != null) mutable.update { it + (groupId to s) }
        return s
    }

    suspend fun put(s: GroupSnapshot) {
        mutable.update { it + (s.group.id to s) }
        file(s.group.id).write(s)
    }

    /**
     * Groups nobody follows any more and nobody opened for a month go, with their backups; leftovers
     * of interrupted writes or set-aside files go after a week. The cache stays at a few hundred KB.
     */
    suspend fun prune(keep: Set<Int>, olderThanMs: Long) = withContext(Dispatchers.IO) {
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        var removed = 0
        dir.listFiles()?.forEach { f ->
            val id = f.name.substringBefore('.').toIntOrNull()
            val leftover = f.name.endsWith(".tmp") || f.name.endsWith(".bad")
            val gone = id != null && id !in keep && f.lastModified() < olderThanMs
            if (gone || (leftover && f.lastModified() < weekAgo)) {
                if (f.delete()) removed++
                if (gone && f.name.endsWith(".json")) mutable.update { it - id!! }
            }
        }
        if (removed > 0) DiagLog.i("Store", "pruned $removed cache files")
    }

    fun ids(): List<Int> = dir.listFiles()?.filter { it.name.endsWith(".json") }?.mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }.orEmpty()

    /** Bytes on disk, for the diagnostic report. */
    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L
}

/**
 * A child the family follows: a name to tap on Home and the team they play in. Everything the
 * site needs to serve their group is kept, plus the names, so Home draws before any fetch.
 */
@Serializable
data class Kid(
    val id: String,
    val name: String,
    val color: Int,
    val sportId: Int,
    val sportName: String,
    val competitionId: Int,
    val competitionName: String,
    val categoryId: Int,
    val categoryName: String,
    val groupId: Int,
    val groupName: String,
    val teamId: Int,
    val teamName: String,
    val crestPath: String = "",
) {
    val ref: GroupRef get() = GroupRef(groupId, sportId, competitionId, categoryId)
}

class KidStore(context: Context) {
    private val file = JsonFile(File(context.filesDir, "kids.json"), ListSerializer(Kid.serializer()))
    private val mutable = MutableStateFlow<List<Kid>?>(null)
    /** Null until read from disk: Home waits for it instead of flashing the empty state. */
    val kids: StateFlow<List<Kid>?> = mutable.asStateFlow()

    suspend fun load() {
        if (mutable.value == null) mutable.value = file.read().orEmpty()
    }

    suspend fun current(): List<Kid> { load(); return mutable.value.orEmpty() }

    suspend fun save(kid: Kid) {
        val list = current()
        val next = if (list.any { it.id == kid.id }) list.map { if (it.id == kid.id) kid else it } else list + kid
        set(next)
    }

    suspend fun remove(id: String) = set(current().filterNot { it.id == id })

    suspend fun reorder(ids: List<String>) {
        val byId = current().associateBy { it.id }
        set(ids.mapNotNull { byId[it] } + current().filter { it.id !in ids })
    }

    suspend fun set(list: List<Kid>) {
        mutable.value = list
        file.write(list)
    }
}
