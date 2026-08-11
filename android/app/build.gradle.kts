import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.let { load(FileInputStream(it)) }
}
val signingProps = Properties().apply {
    localProps.getProperty("signing.propertiesFile")
        ?.let(::File)
        ?.takeIf { it.exists() }
        ?.let { load(FileInputStream(it)) }
}
val hasSigning = signingProps.getProperty("storeFile")?.let { File(it).exists() } == true
val klipyApiKey = localProps.getProperty("klipy.apiKey")
    ?: System.getenv("KLIPY_API_KEY").orEmpty()
val updateBaseUrl = "https://chat.oranges.lt/download/android"
android {
    namespace = "lt.oranges.orangchat"
    compileSdk = 35

    defaultConfig {
        applicationId = "lt.oranges.orangchat"
        minSdk = 31
        targetSdk = 35
        versionCode = 71
        versionName = "1.0.0"
        buildConfigField("String", "KLIPY_API_KEY", "\"$klipyApiKey\"")
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"https://chat.oranges.lt/api/\"")
            buildConfigField("String", "SOCKET_URL", "\"https://chat.oranges.lt\"")
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateBaseUrl/update.json\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE_URL", "\"https://chat.oranges.lt/api/\"")
            buildConfigField("String", "SOCKET_URL", "\"https://chat.oranges.lt\"")
            buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateBaseUrl/update.json\"")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        dex {
            useLegacyPackaging = true
        }
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        checkTestSources = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization.converter)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.socketio.client)
    implementation(libs.firebase.messaging)
    implementation(libs.livekit.android)
    implementation(libs.livekit.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource)
    implementation(libs.media3.database)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.video)
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    testImplementation(libs.junit)
}

val publishUpdate by tasks.registering {
    group = "publishing"
    description = "Publish the signed release APK + update.json to the web root."
    dependsOn("assembleRelease")
    val apkFile = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val versionCode = android.defaultConfig.versionCode!!
    val versionName = android.defaultConfig.versionName!!
    val baseUrl = updateBaseUrl
    val targetDir = File("/var/www/chat.oranges.lt/download/android")
    val apkName = "orangchat-$versionName.apk"
    val changelogSource = project.file("changelog/$versionName.txt")
    val changelogName = "changelog-$versionName.txt"
    doLast {
        val apk = apkFile.get().asFile
        require(changelogSource.isFile) {
            "Write the release notes first: $changelogSource"
        }
        require(apk.exists()) {
            "No signed APK at $apk. Without signing.propertiesFile in local.properties " +
                "the release build is unsigned (app-release-unsigned.apk) and cannot be installed."
        }
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(apk.readBytes())
            .joinToString("") { "%02x".format(it) }
        targetDir.mkdirs()
        apk.copyTo(File(targetDir, apkName), overwrite = true)
        changelogSource.copyTo(File(targetDir, changelogName), overwrite = true)
        File(targetDir, "update.json").writeText(
            """
            {
              "versionCode": $versionCode,
              "versionName": "$versionName",
              "apkUrl": "$baseUrl/$apkName",
              "changelogUrl": "$baseUrl/$changelogName",
              "size": ${apk.length()},
              "sha256": "$sha256"
            }
            """.trimIndent() + "\n",
        )
        logger.lifecycle("Published $apkName - versionCode $versionCode, ${apk.length() / 1024} KiB")
        logger.lifecycle("  -> $targetDir")
    }
}
