package com.mina.legadostudio.domain

import com.mina.legadostudio.data.db.ProjectEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDedupeAnalyzerTest {

    private fun project(
        id: String,
        siteUrl: String = "https://a.com",
        sourceJson: String = "{}",
        updatedAt: Long = 0L,
    ) = ProjectEntity(
        id = id, name = "n-$id", siteUrl = siteUrl, sourceJson = sourceJson,
        stage = "VALIDATED", notes = "", createdAt = 0L, updatedAt = updatedAt,
    )

    private fun source(url: String, name: String = "示例", type: Int? = 0, extra: String = ""): String {
        val typePart = type?.let { ",\"bookSourceType\":$it" } ?: ""
        return """{"bookSourceName":"$name","bookSourceUrl":"$url"$typePart$extra}"""
    }

    // ---------- 身份与分桶 ----------

    @Test fun keepsPortAsPartOfIdentity() {
        // 显式端口是不同身份：同 host 不同端口不得合并
        val a = project("p1", "https://h.com:8080/x", source("https://h.com:8080/x"))
        val b = project("p2", "https://h.com:9090/x", source("https://h.com:9090/x"))
        val report = SourceDedupeAnalyzer.analyze(listOf(a, b))
        assertEquals(0, report.clusters.size)
        assertEquals(0, report.stats.redundantCopies)
        val ids = report.items.associateBy { it.projectId }
        assertEquals(8080, ids.getValue("p1").identity!!.port)
        assertEquals(9090, ids.getValue("p2").identity!!.port)
    }

    @Test fun keepsPathAndQueryAsPartOfIdentity() {
        // 路径/查询是身份的一部分：同域不同路径不并桶
        val a = project("p1", "https://h.com/a", source("https://h.com/a"))
        val b = project("p2", "https://h.com/b", source("https://h.com/b"))
        val c = project("p3", "https://h.com/a?k=1", source("https://h.com/a?k=1"))
        val report = SourceDedupeAnalyzer.analyze(listOf(a, b, c))
        assertEquals(0, report.clusters.size)
        assertEquals(3, report.items.mapNotNull { it.clusterKey }.distinct().size)
        val p3 = report.items.first { it.projectId == "p3" }
        assertEquals("/a?k=1", p3.identity!!.pathQuery)
    }

    @Test fun keepsLegadoHashTagsAsPartOfIdentity() {
        // Legado 约定：bookSourceUrl 的 # 后是源标签，改标签 = 改身份，不并入同一桶
        val a = project("p1", "https://h.com/x", source("https://h.com/x#有声"))
        val b = project("p2", "https://h.com/x", source("https://h.com/x#完结"))
        val report = SourceDedupeAnalyzer.analyze(listOf(a, b))
        assertEquals(0, report.clusters.size)
        assertEquals(listOf("有声"), report.items.first { it.projectId == "p1" }.identity!!.tags)
        assertEquals(listOf("完结"), report.items.first { it.projectId == "p2" }.identity!!.tags)
    }

    @Test fun bookSourceTypeIsRecordedButDoesNotMergeDifferentContent() {
        // 同 URL 不同 bookSourceType → 冲突而非淘汰；type 记录在身份里
        val a = project("p1", "https://h.com/x", source("https://h.com/x", type = 0))
        val b = project("p2", "https://h.com/x", source("https://h.com/x", type = 1))
        val report = SourceDedupeAnalyzer.analyze(listOf(a, b))
        assertEquals(1, report.clusters.size)
        val cluster = report.clusters.first()
        assertEquals(SourceDedupeAnalyzer.ACTION_NONE, cluster.action)
        assertFalse(cluster.identical)
        assertTrue(report.conflicts.any { it.kind == SourceDedupeAnalyzer.CONFLICT_SAME_URL_DIFF_JSON })
        assertEquals(0, report.items.first { it.projectId == "p1" }.identity!!.type)
        assertEquals(1, report.items.first { it.projectId == "p2" }.identity!!.type)
    }

    @Test fun canonicalizationIgnoresSchemeAndHostCaseOnly() {
        // 规范化只抹掉 scheme/host 大小写：这两类差异不拆桶
        val a = project("p1", "https://A.com/x", source("HTTPS://a.com/x"))
        val b = project("p2", "https://a.com/x", source("https://a.com/x"))
        val report = SourceDedupeAnalyzer.analyze(listOf(a, b))
        assertEquals(1, report.clusters.size)
        assertTrue(report.clusters.first().memberIds.containsAll(listOf("p1", "p2")))
    }

    @Test fun trailingSlashOnPathIsPreservedNotNormalized() {
        // 路径尾部 / 语义可能不同（目录 vs 文件），逐字节保留 → 不同身份
        val a = project("p1", "https://h.com/x", source("https://h.com/x"))
        val b = project("p2", "https://h.com/x/", source("https://h.com/x/"))
        val report = SourceDedupeAnalyzer.analyze(listOf(a, b))
        assertTrue(report.clusters.isEmpty())
        assertEquals("https://h.com/x", report.items.first { it.projectId == "p1" }.identity!!.canonicalUrl)
        assertEquals("https://h.com/x/", report.items.first { it.projectId == "p2" }.identity!!.canonicalUrl)
    }

    @Test fun nonHttpSchemesAndUserinfoAreUnsafeIdentities() {
        // ftp / 无 scheme / userinfo 内嵌：都不给 u: 安全身份
        assertNull(SourceDedupeAnalyzer.canonicalUrlOf("ftp://h.com/x"))
        assertNull(SourceDedupeAnalyzer.canonicalUrlOf("h.com/x"))          // 缺 scheme
        assertNull(SourceDedupeAnalyzer.canonicalUrlOf("//h.com/x"))        // scheme-relative
        assertNull(SourceDedupeAnalyzer.canonicalUrlOf("https://u:p@h.com/x")) // userinfo 拒绝
        assertEquals("https://h.com/x", SourceDedupeAnalyzer.canonicalUrlOf("HTTPS://H.COM/x"))
    }

    @Test fun sameUrlDifferentJsonReportsConflictKeepsEverything() {
        val a = project("p1", "https://h.com/x", source("https://h.com/x", extra = ",\"ruleContent\":{\"content\":\"#a\"}"))
        val b = project("p2", "https://h.com/x", source("https://h.com/x", extra = ",\"ruleContent\":{\"content\":\"#b\"}"))
        val report = SourceDedupeAnalyzer.analyze(listOf(a, b))
        val cluster = report.clusters.single()
        assertEquals(SourceDedupeAnalyzer.ACTION_NONE, cluster.action)
        assertEquals("", cluster.keepProjectId)
        assertEquals(listOf("p1", "p2"), cluster.memberIds)
        assertTrue(report.conflicts.single { it.kind == SourceDedupeAnalyzer.CONFLICT_SAME_URL_DIFF_JSON }
            .detail.contains("p1"))
        // 任何冲突都不产生保留建议
        assertTrue(report.clusters.all { it.action != SourceDedupeAnalyzer.ACTION_EXPORT_ONE })
    }

    @Test fun identicalJsonSuggestsKeepOneOnly() {
        // 键序不同但结构相同 → 完全一致；建议导出保留 1 条（updatedAt 最新者）
        val jsonA = """{"bookSourceName":"n","bookSourceUrl":"https://h.com/x","bookSourceType":0}"""
        val jsonB = """{"bookSourceType":0,"bookSourceUrl":"https://h.com/x","bookSourceName":"n"}"""
        val old = project("p-old", "https://h.com/x", jsonA, updatedAt = 10)
        val new = project("p-new", "https://h.com/x", jsonB, updatedAt = 20)
        val report = SourceDedupeAnalyzer.analyze(listOf(old, new))
        val cluster = report.clusters.single()
        assertEquals(SourceDedupeAnalyzer.ACTION_EXPORT_ONE, cluster.action)
        assertEquals("p-new", cluster.keepProjectId)
        assertTrue(cluster.identical)
        assertEquals(1, report.stats.redundantCopies)
        assertTrue(report.conflicts.isEmpty())
    }

    @Test fun identicalKeepPickIsDeterministicOnTie() {
        // updatedAt 并列时取最小 projectId，保证可复现
        val a = project("b", "https://h.com/x", source("https://h.com/x"), updatedAt = 5)
        val b = project("a", "https://h.com/x", source("https://h.com/x"), updatedAt = 5)
        val report = SourceDedupeAnalyzer.analyze(listOf(a, b))
        assertEquals("a", report.clusters.single().keepProjectId)
    }

    @Test fun identicalClusterWithUrlMismatchNeverRecommendsFold() {
        // 关键红线：内容全一致但有一个成员 siteUrl↔sourceUrl 冲突 → action=none
        val good = project("good", "https://h.com/x", source("https://h.com/x"))
        val mismatched = project("bad", "https://evil.com/x", source("https://h.com/x"))
        val report = SourceDedupeAnalyzer.analyze(listOf(good, mismatched))
        val cluster = report.clusters.single()
        assertEquals(SourceDedupeAnalyzer.ACTION_NONE, cluster.action)
        assertEquals("", cluster.keepProjectId)
        assertTrue(cluster.identical) // 内容仍如实标记一致
        assertEquals(0, report.stats.redundantCopies)
        // 同时 URL_MISMATCH 冲突照常上报
        assertTrue(report.conflicts.any {
            it.kind == SourceDedupeAnalyzer.CONFLICT_URL_MISMATCH && it.projectIds == listOf("bad")
        })
    }

    @Test fun rawFallbackClusterNeverRecommendsFold() {
        // 非 http(s) bookSourceUrl：即便内容完全一致，身份不确定 → none。
        // siteUrl 与 bookSourceUrl 用同一个 ftp 地址，隔离掉 URL_MISMATCH 干扰。
        val json = source("ftp://h.com/x")
        val a = project("p1", "ftp://h.com/x", json)
        val b = project("p2", "ftp://h.com/x", json)
        val report = SourceDedupeAnalyzer.analyze(listOf(a, b))
        val cluster = report.clusters.single()
        assertTrue(cluster.clusterKey.startsWith("r:"))
        assertEquals(SourceDedupeAnalyzer.ACTION_NONE, cluster.action)
        assertTrue(cluster.reason.contains("身份不确定"))
        assertEquals(0, report.stats.redundantCopies)
        assertTrue(report.conflicts.isEmpty()) // 内容一致不是冲突，只是不给建议
    }

    @Test fun missingSourceUrlSiteFallbackNeverRecommendsFold() {
        // 缺 bookSourceUrl：按 siteUrl 兜底分组呈现重复候选，但身份不确定 → none
        val noUrl = """{"bookSourceName":"n","bookSourceType":0}"""
        val a = project("p1", "https://h.com/x", noUrl)
        val b = project("p2", "https://h.com/x", noUrl)
        val report = SourceDedupeAnalyzer.analyze(listOf(a, b))
        val cluster = report.clusters.single()
        assertTrue(cluster.clusterKey.startsWith("s:"))
        assertEquals(SourceDedupeAnalyzer.ACTION_NONE, cluster.action)
        assertEquals(2, report.stats.missingSourceUrl)
        assertEquals(0, report.stats.redundantCopies)
    }

    @Test fun urlMismatchBetweenSiteUrlAndSourceJsonReportsConflict() {
        val p = project("p1", siteUrl = "https://other.com/x", sourceJson = source("https://h.com/x"))
        val report = SourceDedupeAnalyzer.analyze(listOf(p))
        val conflict = report.conflicts.single()
        assertEquals(SourceDedupeAnalyzer.CONFLICT_URL_MISMATCH, conflict.kind)
        assertEquals(listOf("p1"), conflict.projectIds)
        assertTrue(conflict.detail.contains("other.com"))
        assertTrue(conflict.detail.contains("h.com"))
        // 保存前置接法也报同样的冲突
        assertTrue(SourceDedupeAnalyzer.urlMismatchBetween("https://other.com/x", "https://h.com/x") != null)
        assertNull(SourceDedupeAnalyzer.urlMismatchBetween("https://h.com/x", "https://h.com/x"))
        // 尾部 / 现在语义不同：视为冲突而非误当一致
        assertTrue(SourceDedupeAnalyzer.urlMismatchBetween("https://h.com/x/", "https://h.com/x") != null)
    }

    @Test fun unparseableJsonNeverClustersAndNeverDrops() {
        val broken = project("broken", "https://h.com/x", "{not json")
        val array = project("array", "https://h.com/x", "[1,2]")
        val good = project("good", "https://h.com/x", source("https://h.com/x"))
        val report = SourceDedupeAnalyzer.analyze(listOf(broken, array, good))
        // 无法解析的两条：identity=null、clusterKey=null、不进任何簇、不计冲突
        val ids = report.items.associateBy { it.projectId }
        assertNull(ids.getValue("broken").identity)
        assertNull(ids.getValue("broken").clusterKey)
        assertNull(ids.getValue("array").identity)
        assertTrue(report.clusters.isEmpty())
        assertEquals(2, report.stats.unparseableJson)
        assertEquals(3, report.stats.totalProjects) // 全部保留
    }

    @Test fun reportIsDeterministicRegardlessOfInputOrder() {
        val list = listOf(
            project("c", "https://h.com/x", source("https://h.com/x")),
            project("a", "https://h.com/x", source("https://h.com/x")),
            project("b", "https://other.com/y", source("https://other.com/y", extra = ",\"k\":1")),
        )
        val r1 = SourceDedupeAnalyzer.analyze(list)
        val r2 = SourceDedupeAnalyzer.analyze(list.reversed())
        assertEquals(r1.items, r2.items)
        assertEquals(r1.clusters, r2.clusters)
        assertEquals(r1.conflicts, r2.conflicts)
        assertEquals(r1.stats, r2.stats)
    }

    @Test fun analyzeRowsMatchesAnalyzeOnEntities() {
        val list = listOf(
            project("a", "https://h.com/x", source("https://h.com/x"), updatedAt = 1),
            project("b", "https://h.com/x", source("https://h.com/x"), updatedAt = 2),
        )
        val viaEntities = SourceDedupeAnalyzer.analyze(list)
        val viaRows = SourceDedupeAnalyzer.analyzeRows(
            list.map { SourceDedupeAnalyzer.Row(it.id, it.siteUrl, it.sourceJson, it.updatedAt) }
        )
        assertEquals(viaEntities.clusters, viaRows.clusters)
        assertEquals(viaEntities.items.map { it.projectId to it.clusterKey }, viaRows.items.map { it.projectId to it.clusterKey })
    }

    @Test fun identifyReturnsIdentityAndBucketForSavePath() {
        val item = SourceDedupeAnalyzer.identify("p1", "https://h.com/x", source("https://h.com/x#tag1"))
        assertEquals("u:https://h.com/x#tag1", item.clusterKey)
        assertEquals(listOf("tag1"), item.identity!!.tags)
        // 无法解析时保存路径同样拿到「不参与去重」的独立项
        val broken = SourceDedupeAnalyzer.identify("p2", "https://h.com/x", "{broken")
        assertNull(broken.clusterKey)
        assertNull(broken.identity)
        // 非 http(s) URL：落到 r: 桶，保存方可据此提示身份不确定
        val ftp = SourceDedupeAnalyzer.identify("p3", "https://h.com/x", source("ftp://h.com/x"))
        assertTrue(ftp.clusterKey!!.startsWith("r:"))
    }

    @Test fun differentDomainsWithSamePathStaySeparate() {
        // 回归旧 bug：旧逻辑按注册域合并裁剪；现在 a.com/x 与 b.com/x 是完全不同身份
        val a = project("p1", "https://a.com/x", source("https://a.com/x"))
        val b = project("p2", "https://b.com/x", source("https://b.com/x"))
        val report = SourceDedupeAnalyzer.analyze(listOf(a, b))
        assertTrue(report.clusters.isEmpty())
        assertNotEquals(
            report.items.first { it.projectId == "p1" }.clusterKey,
            report.items.first { it.projectId == "p2" }.clusterKey,
        )
    }
}
