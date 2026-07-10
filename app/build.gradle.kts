import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing credentials live outside the repo entirely (see .gitignore's signing block):
// a plain properties file (storeFile/storePassword/keyAlias/keyPassword), defaulting to
// ~/keystores/cambium-release.credentials, overridable via CAMBIUM_SIGNING_PROPERTIES. When the
// file is absent (CI, other machines), the release build type simply stays unsigned.
val releaseSigning = Properties().apply {
    val path = System.getenv("CAMBIUM_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/keystores/cambium-release.credentials"
    val file = File(path)
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "dev.forgesworn.cambium"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.forgesworn.cambium"
        minSdk = 27
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            releaseSigning.getProperty("storeFile")?.let { path ->
                storeFile = file(path)
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigning.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        viewBinding = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = false
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.nostr.sdk)
    implementation(libs.zxing.android.embedded)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

// A literal "--" inside an XML comment is illegal per the XML spec, and Android's resource
// merger/data-binding layout parser reject it outright with a build failure rather than a
// warning -- this has been re-introduced by hand several times in review (an em-dash-style "--"
// used as a sentence separator inside a multi-line comment), always caught only by the build
// itself failing. Cheap enough to check on every build rather than rely on remembering.
tasks.register("checkXmlCommentHyphens") {
    description = "Fails if any XML comment under src/main contains a literal '--'."
    group = "verification"

    val xmlFiles = fileTree("src/main") { include("**/*.xml") }
    inputs.files(xmlFiles)

    doLast {
        val commentPattern = Regex("<!--(.*?)-->", RegexOption.DOT_MATCHES_ALL)
        val offenders = mutableListOf<String>()
        xmlFiles.forEach { file ->
            val text = file.readText()
            for (match in commentPattern.findAll(text)) {
                if ("--" in match.groupValues[1]) {
                    val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                    offenders += "${file.relativeTo(projectDir)}:$line"
                }
            }
        }
        check(offenders.isEmpty()) {
            "XML comments must not contain '--':\n" + offenders.joinToString("\n") { "  $it" }
        }
    }
}

tasks.named("preBuild") {
    dependsOn("checkXmlCommentHyphens")
}
