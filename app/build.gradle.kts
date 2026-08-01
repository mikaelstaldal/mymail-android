// Not java.util.Properties inline below: in the Kotlin DSL `java` is the Java plugin's extension,
// so a fully-qualified reference to the package does not resolve.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.openapi.generator)
}

// Signing key shared by the My* apps. The default ~/.android/debug.keystore would satisfy that,
// but it is a poor trust anchor: world-readable, fixed password "android", and shared by every
// debug APK built on the machine
//
// Configure it in local.properties (kept out of version control), or through the matching
// environment variables for CI:
//
//     debugKeystore=/path/to/staldal-apps.keystore   DEBUG_KEYSTORE
//     debugKeystorePassword=…                        DEBUG_KEYSTORE_PASSWORD
//     debugKeyAlias=staldal-apps                     DEBUG_KEY_ALIAS
//     debugKeyPassword=…                             DEBUG_KEY_PASSWORD
//
// Absent or incomplete, the build still works but falls back to the default debug key and says so.
// Both apps then fall back alike, so the integration keeps working — it is the trust boundary that
// weakens, which is exactly the thing that must not happen quietly.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingProperty(key: String, env: String): String? =
    (localProperties.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

android {
    namespace = "nu.staldal.mymail"
    compileSdk = 36

    signingConfigs {
        // Overrides the built-in debug config, which debug and androidTest builds already use.
        getByName("debug") {
            val store = signingProperty("debugKeystore", "DEBUG_KEYSTORE")?.let(::file)
            val storePw = signingProperty("debugKeystorePassword", "DEBUG_KEYSTORE_PASSWORD")
            val alias = signingProperty("debugKeyAlias", "DEBUG_KEY_ALIAS")
            val keyPw = signingProperty("debugKeyPassword", "DEBUG_KEY_PASSWORD")
            if (store?.exists() == true && storePw != null && alias != null && keyPw != null) {
                storeFile = store
                storeType = "PKCS12"
                storePassword = storePw
                keyAlias = alias
                keyPassword = keyPw
            } else {
                logger.warn(
                    "MyMail: no shared debug signing key configured (see app/build.gradle.kts); " +
                        "falling back to the default debug keystore."
                )
            }
        }
    }

    defaultConfig {
        applicationId = "nu.staldal.mymail"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    
    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set(
        providers.gradleProperty("openApiSpecPath")
            .getOrElse("$rootDir/../mymail/openapi.yaml")
    )
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    templateDir.set("$rootDir/kotlin-client")
    apiPackage.set("nu.staldal.mymail.api")
    modelPackage.set("nu.staldal.mymail.model")
    configOptions.set(
        mapOf(
            "library" to "jvm-retrofit2",
            "serializationLibrary" to "kotlinx_serialization",
            "useCoroutines" to "true",
            "dateLibrary" to "java8",
        )
    )
}

kotlin.sourceSets["main"].kotlin.srcDir(
    layout.buildDirectory.dir("generated/openapi/src/main/kotlin")
)

afterEvaluate {
    tasks.named("compileDebugKotlin") { dependsOn("openApiGenerate") }
    tasks.named("compileReleaseKotlin") { dependsOn("openApiGenerate") }
    tasks.named("compileDebugUnitTestKotlin") { dependsOn("openApiGenerate") }
    tasks.named("kspDebugKotlin") { dependsOn("openApiGenerate") }
    tasks.named("kspReleaseKotlin") { dependsOn("openApiGenerate") }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.compose)

    // Compose
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.compose.ui.test.junit4)

    // Navigation
    implementation(libs.navigation.compose)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)

    // Retrofit + OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.retrofit.converter.scalars)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // kotlinx
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Security
    implementation(libs.security.crypto)

    // MIME4J
    implementation(libs.apache.mime4j.core)
    implementation(libs.apache.mime4j.dom)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
