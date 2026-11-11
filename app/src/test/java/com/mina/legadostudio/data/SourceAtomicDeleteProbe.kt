package com.mina.legadostudio.data

import com.mina.legadostudio.data.db.ProjectEntity
import com.mina.legadostudio.data.db.StudioDao
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

/** Real production default DAO method; JDBC supplies SQL IO + Room's transaction boundary.
 * This is NOT a generated Room/Android integration test. */
private class Fixture(val db: Connection) {
    var afterRead: (() -> Unit)? = null
    var failDelete = false
    fun rows(): List<ProjectEntity> = db.createStatement().use { s ->
        s.executeQuery("SELECT * FROM projects ORDER BY updatedAt DESC").use { r ->
            buildList { while (r.next()) add(ProjectEntity(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getLong(7),r.getLong(8))) }
        }
    }
    fun save(p: ProjectEntity) {
        db.prepareStatement("INSERT OR REPLACE INTO projects VALUES (?,?,?,?,?,?,?,?)").use {
            listOf(p.id,p.name,p.siteUrl,p.sourceJson,p.stage,p.notes,p.createdAt,p.updatedAt).forEachIndexed { i,v -> it.setObject(i+1,v) }
            it.executeUpdate()
        }
    }
    private val io = Proxy.newProxyInstance(StudioDao::class.java.classLoader,arrayOf(StudioDao::class.java)) { _, method, args ->
        when(method.name) {
            "allProjects" -> rows().also { afterRead?.also { afterRead = null }?.invoke() }
            "deleteProjects" -> {
                @Suppress("UNCHECKED_CAST") val ids = args!![0] as List<String>
                db.prepareStatement("DELETE FROM projects WHERE id IN (${ids.joinToString(",") { "?" }})").use {
                    ids.forEachIndexed { i,id -> it.setString(i+1,id) }; val n = it.executeUpdate()
                    if (failDelete) error("injected after delete")
                    n
                }
            }
            else -> error("Unexpected IO ${method.name}")
        }
    } as StudioDao
    val dao = object : StudioDao by io {
        override suspend fun deleteProjectsFromSnapshot(expected: List<ProjectEntity>, ids: List<String>): Int {
            db.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            try {
                val n = super<StudioDao>.deleteProjectsFromSnapshot(expected,ids)
                db.createStatement().use { it.execute("COMMIT") }
                return n
            } catch (t: Throwable) {
                db.createStatement().use { it.execute("ROLLBACK") }
                throw t
            }
        }
    }
    val repo = ProjectRepository(dao)
}
fun main(args: Array<String>) = runBlocking {
    Class.forName("org.sqlite.JDBC")
    val url = "jdbc:sqlite:${args[0]}"
    DriverManager.getConnection(url).use { db ->
        db.createStatement().use { it.execute("CREATE TABLE projects (id TEXT PRIMARY KEY,name TEXT,siteUrl TEXT,sourceJson TEXT,stage TEXT,notes TEXT,createdAt INTEGER,updatedAt INTEGER)") }
        val f = Fixture(db)
        val a = ProjectEntity("a","A","https://a.example","{  }","draft","",1,2)
        val b = a.copy(id="b",siteUrl="https://b.example")
        fun reset() { db.createStatement().use { it.execute("DELETE FROM projects") }; f.save(a); f.save(b) }
        reset()
        check(f.repo.deleteFromSnapshot(listOf(a,b),listOf("a")) == 1)
        check(f.rows() == listOf(b))
        reset()
        check(f.repo.deleteFromSnapshot(listOf(a,b),listOf("a","b")) == 2 && f.rows().isEmpty())
        // Every entity field, including unchanged timestamp + changed source/metadata, is compared.
        for (changed in listOf(a.copy(name="new"),a.copy(siteUrl="https://else.example"),a.copy(sourceJson="{\"x\":1}"),a.copy(stage="done"),a.copy(notes="new"),a.copy(createdAt=3),a.copy(updatedAt=3))) {
            reset(); f.save(changed)
            check(runCatching { f.repo.deleteFromSnapshot(listOf(a,b),listOf("a")) }.isFailure)
            check(f.rows().toSet() == setOf(changed,b))
        }
        reset(); f.save(a.copy(id="inserted"))
        check(runCatching { f.repo.deleteFromSnapshot(listOf(a,b),listOf("a","b")) }.isFailure)
        check(f.rows().size == 3)
        reset(); db.createStatement().use { it.execute("DELETE FROM projects WHERE id='a'") }; f.save(a.copy(sourceJson="{\"rebuilt\":true}"))
        check(runCatching { f.repo.deleteFromSnapshot(listOf(a,b),listOf("a")) }.isFailure)
        check(f.rows().size == 2)
        reset(); f.failDelete = true
        check(runCatching { f.repo.deleteFromSnapshot(listOf(a,b),listOf("a")) }.isFailure)
        check(f.rows().toSet() == setOf(a,b)); f.failDelete = false
        // A second SQLite connection attempts a same-ID replacement or insertion between read and DELETE.
        for (replacement in listOf(a.copy(sourceJson="{\"late\":true}"),a.copy(id="late"))) {
            reset()
            val started = CountDownLatch(1); val done = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            lateinit var writer: Thread
            f.afterRead = {
                writer = Thread {
                    try {
                        DriverManager.getConnection(url).use { other ->
                            other.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
                            started.countDown(); Fixture(other).save(replacement)
                        }
                    } catch(t: Throwable) { failure.set(t) } finally { done.countDown() }
                }.apply { start() }
                check(started.await(2,TimeUnit.SECONDS))
                check(!done.await(150,TimeUnit.MILLISECONDS)) { "writer escaped transaction" }
            }
            check(f.repo.deleteFromSnapshot(listOf(a,b),listOf("a","b")) == 2)
            check(done.await(5,TimeUnit.SECONDS)); writer.join(); failure.get()?.let { throw it }
            check(f.rows() == listOf(replacement)) { "late write must survive, never widen deletion" }
        }
        reset()
        check(runCatching { f.repo.deleteFromSnapshot(listOf(a,b),listOf("unknown")) }.isFailure)
        check(f.rows().size == 2)
        db.createStatement().use { it.execute("DELETE FROM projects") }
        val large = (1..1001).map { a.copy(id="p$it") }; large.forEach(f::save)
        check(f.repo.deleteFromSnapshot(large,large.map { it.id }) == 1001 && f.rows().isEmpty())
        println("PASS exact domain/all deletion; all entity fields; insert/rebuild conflicts; rollback; concurrent same-ID replacement/insertion; 1001-row chunking")
    }
}
