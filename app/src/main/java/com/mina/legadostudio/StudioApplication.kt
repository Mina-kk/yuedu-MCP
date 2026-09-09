package com.mina.legadostudio

import android.app.Application
import com.google.gson.GsonBuilder
import com.mina.legadostudio.analyzer.HtmlAnalyzer
import com.mina.legadostudio.data.LegacyProjectMigrator
import com.mina.legadostudio.data.ProjectRepository
import com.mina.legadostudio.data.db.StudioDatabase
import com.mina.legadostudio.data.db.OperationLogEntity
import com.mina.legadostudio.diagnostic.CrashLogStore
import com.mina.legadostudio.diagnostic.DiagnosticSnapshotStore
import com.mina.legadostudio.domain.BookSourceValidator
import com.mina.legadostudio.mcp.StudioLog
import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.network.HttpLogRecorder
import com.mina.legadostudio.network.RuntimeConfigStore
import com.mina.legadostudio.skills.KnowledgeRepository
import com.mina.legadostudio.skills.SkillRepository
import com.mina.legadostudio.runtime.EmbeddedLegadoRuntime
import com.mina.legadostudio.runtime.RhinoEvaluator
import com.mina.legadostudio.verification.DomainModeStore
import com.mina.legadostudio.verification.RuntimeCookieStore
import com.mina.legadostudio.verification.VerificationCoordinator
import com.mina.legadostudio.verification.VerificationWebViewStateStore
import com.mina.legadostudio.verification.WebViewPageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant

class StudioApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        crashLogs.install()
        wireOperationLogs()
        scope.launch {
            LegacyProjectMigrator(this@StudioApplication, projects).migrate()
        }
    }

    val crashLogs by lazy { CrashLogStore(this) }
    val snapshots by lazy { DiagnosticSnapshotStore(this) }
    val gson by lazy { GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create() }
    val database by lazy { StudioDatabase.get(this) }
    val projects by lazy { ProjectRepository(database.dao()) }
    val skills by lazy { SkillRepository(this) }
    val knowledge by lazy { KnowledgeRepository(this) }
    val validator by lazy { BookSourceValidator() }
    val cookieStore by lazy { RuntimeCookieStore(this) }
    val domainModes by lazy { DomainModeStore(this) }
    val verificationWebState by lazy { VerificationWebViewStateStore(this) }
    val runtimeConfig by lazy { RuntimeConfigStore(this) }
    val httpLogs by lazy { HttpLogRecorder(this, database.dao(), gson) }
    val fetcher by lazy { HttpFetcher(cookieStore::headerFor, httpLogs, runtimeConfig::userAgent, { runtimeConfig.bookSourceType }) }
    val webViewLoader by lazy { WebViewPageLoader(this, runtimeConfig::userAgent) }
    val verification by lazy { VerificationCoordinator(this, database.dao(), cookieStore, verificationWebState) }
    val taskContexts by lazy { com.mina.legadostudio.mcp.TaskContextStore() }
    val analyzer by lazy { HtmlAnalyzer() }
    val runtime by lazy { EmbeddedLegadoRuntime(fetcher, validator, rhino = RhinoEvaluator(fetcher, gson, webViewLoader, cookieStore, runtimeConfig::userAgent), webViewLoader = webViewLoader, domainModes = domainModes, httpLogs = httpLogs) }

    private fun wireOperationLogs() {
        StudioLog.sink = StudioLog.Sink { level, category, message, detail ->
            scope.launch {
                val dao = database.dao()
                dao.addOperationLog(OperationLogEntity(level = level, category = category, message = message, detail = detail))
                dao.trimOperationLogs(500)
            }
        }
        StudioLog.reader = StudioLog.Reader { limit ->
            runBlocking {
                database.dao().latestOperationLogs(limit).asReversed().map { log ->
                    "${Instant.ofEpochMilli(log.createdAt)} ${log.message}"
                }
            }
        }
    }
}
