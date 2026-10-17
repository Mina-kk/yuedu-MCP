import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.HashSet
import java.util.concurrent.Callable

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.mina.legadostudio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mina.legadostudio"
        minSdk = 26
        targetSdk = 36
        versionCode = 189
        versionName = "1.2.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 构建标识：显式 -PbuildRevision 优先，CI 环境变量 GITHUB_SHA 次之，
        // 普通本地开发缺省 "development"。不在配置阶段执行 git。
        val buildRevision = (
            providers.gradleProperty("buildRevision").orNull
                ?: providers.environmentVariable("GITHUB_SHA").orNull
                ?: "development"
            ).also {
                require(it == "development" || it.matches(Regex("[0-9a-f]{7,40}"))) {
                    "buildRevision must be 'development' or a 7-40 char lowercase hex commit id, got: $it"
                }
            }
        buildConfigField("String", "BUILD_REVISION", "\"$buildRevision\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    signingConfigs {
        create("release") {
            val path = System.getenv("RELEASE_STORE_FILE")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/INDEX.LIST"
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":legado-rhino"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("dev.chrisbanes.haze:haze:1.7.2")
    implementation("dev.chrisbanes.haze:haze-materials:1.7.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("androidx.webkit:webkit:1.15.0")
    implementation("androidx.security:security-crypto:1.1.0")

    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okhttp3:okhttp-brotli:5.3.2")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("cn.wanghaomiao:JsoupXpath:2.5.3")
    implementation("com.jayway.jsonpath:json-path:3.0.0")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    }
    implementation("io.ktor:ktor-server-cio:3.5.2") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    testImplementation("io.ktor:ktor-server-test-host:3.5.2")
}

// 真实联网测试隔离：默认所有 Test 任务排除 LiveNetworkTest category，
// 仅独立的 liveTestDebug 任务按该 category 执行（见 BUILDING.md）。
val liveNetworkTestCategory = "com.mina.legadostudio.testsupport.LiveNetworkTest"

tasks.withType<Test>().configureEach {
    if (name != "liveTestDebug") {
        useJUnit { excludeCategories(liveNetworkTestCategory) }
    }
}

val liveTestDebug = tasks.register<Test>("liveTestDebug") {
    description = "Runs real-network LiveNetworkTest cases (explicit opt-in; never part of the default test run)."
    group = "verification"
    useJUnit {
        includeCategories(liveNetworkTestCategory)
        // 用 Java HashSet 清空排除集：Kotlin emptySet() 的 EmptySet 会在
        // 序列化进 test worker 配置时因 worker classloader 缺 kotlin-stdlib 而崩溃
        setExcludeCategories(HashSet())
    }
    systemProperty("legadostudio.liveTests", "true")
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("live network tests are never cached") { true }
    // 报告/binary 与默认 testDebugUnitTest 完全隔离
    reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/liveTestDebug"))
    reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/liveTestDebug"))
    binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/liveTestDebug/binary"))
}

afterEvaluate {
    val debugUnitTest = tasks.getByName("testDebugUnitTest") as Test
    liveTestDebug.configure {
        testClassesDirs = debugUnitTest.testClassesDirs
        classpath = debugUnitTest.classpath
        workingDir(debugUnitTest.workingDir)
        // 复用 debugUnitTest 的前置依赖，但不依赖 testDebugUnitTest 任务本身
        dependsOn(Callable { debugUnitTest.taskDependencies.getDependencies(debugUnitTest) })
    }
}
