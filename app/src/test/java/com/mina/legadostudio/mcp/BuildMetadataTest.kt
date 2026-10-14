package com.mina.legadostudio.mcp

import com.mina.legadostudio.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * get_app_info 构建标识契约：buildRevision/buildType 平铺字段来自 BuildConfig，
 * buildRevision 只允许 "development" 或 7-40 位小写 hex（构建脚本已强制校验）。
 */
class BuildMetadataTest {

    @Test
    fun exposesFlatRevisionAndType() {
        val meta = buildMetadata()
        assertEquals(BuildConfig.BUILD_REVISION, meta["buildRevision"])
        assertEquals(BuildConfig.BUILD_TYPE, meta["buildType"])
    }

    @Test
    fun revisionIsDevelopmentOrLowerHex() {
        val revision = buildMetadata().getValue("buildRevision")
        assertTrue(
            "buildRevision 必须是 development 或 7-40 位小写 hex，实际：$revision",
            revision == "development" || revision.matches(Regex("[0-9a-f]{7,40}")),
        )
    }

    @Test
    fun metadataHasExactlyTwoKeys() {
        assertEquals(setOf("buildRevision", "buildType"), buildMetadata().keys)
    }
}
