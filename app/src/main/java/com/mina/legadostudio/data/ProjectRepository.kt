package com.mina.legadostudio.data

import com.mina.legadostudio.data.db.ProjectEntity
import com.mina.legadostudio.data.db.SourceRevisionEntity
import com.mina.legadostudio.data.db.StudioDao
import com.mina.legadostudio.domain.SourceDedupeAnalyzer
import kotlinx.coroutines.flow.Flow

class ProjectRepository(private val dao: StudioDao) {
    fun observe(): Flow<List<ProjectEntity>> = dao.observeProjects()
    suspend fun list(): List<ProjectEntity> = dao.allProjects()
    suspend fun get(id: String): ProjectEntity? = dao.project(id)
    suspend fun save(value: ProjectEntity): ProjectEntity {
        val old = dao.project(value.id)
        val next = value.copy(updatedAt = System.currentTimeMillis())
        dao.saveProject(next)
        if (old?.sourceJson != next.sourceJson) dao.addRevision(SourceRevisionEntity(projectId = next.id, sourceJson = next.sourceJson, note = next.stage))
        return next
    }
    suspend fun delete(ids: List<String>): Int = if (ids.isEmpty()) 0 else dao.deleteProjects(ids.distinct())
    suspend fun export(id: String): String = dao.project(id)?.sourceJson ?: error("项目不存在")

    /** 保守去重扫描入口（纯函数包装）：只出报告，绝不删数据。供 MCP/UI 稍后接线。 */
    suspend fun dedupeReport(): SourceDedupeAnalyzer.DedupeReport =
        SourceDedupeAnalyzer.analyze(dao.allProjects())
}
