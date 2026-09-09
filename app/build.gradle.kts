import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        versionCode = 138
        versionName = "1.0.118"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
