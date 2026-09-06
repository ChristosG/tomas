plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "gr.dimitris.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "gr.dimitris.app"
        minSdk = 26
        targetSdk = 37
        // The release workflow passes both from the git tag (-PversionName=0.2 -PversionCode=<run>).
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1"
        // Ours, not the stock one: it answers the first-run "whose phone is this?" before any test
        // opens a screen. See DimitrisTestRunner.
        testInstrumentationRunner = "gr.dimitris.app.DimitrisTestRunner"
    }

    // Release signing comes from the environment (the GitHub release workflow sets these from the
    // repository secrets; locally, source ~/.dimitris-app/README.txt). Without them a release build
    // falls back to the debug key so that `assembleRelease` still works on any machine.
    val keystorePath = System.getenv("DIMITRIS_KEYSTORE_PATH")
    val hasReleaseKey = keystorePath != null && file(keystorePath).exists()
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("DIMITRIS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DIMITRIS_KEY_ALIAS")
                keyPassword = System.getenv("DIMITRIS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // The Anthropic SDK pulls in three Apache HttpComponents jars, and each of them carries its
    // own META-INF/DEPENDENCIES; the packager refuses to choose between them and fails the build.
    // Those files are Maven's dependency listings, not code or licences, so dropping them costs
    // nothing — META-INF/LICENSE and NOTICE stay in the APK. The other two names are the usual
    // companions of the same clash and are excluded pre-emptively rather than one build at a time.
    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
            "META-INF/DEPENDENCIES",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    constraints {
        // Room 2.8.4's MigrationTestHelper needs kotlinx-serialization 1.8.x; savedstate 1.5.0 pins 1.7.3
        // through lifecycle-viewmodel-compose, which throws AbstractMethodError on the instrumented test.
        // Raising the floor (not forcing) keeps main and androidTest aligned.
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1") { because("Room 2.8.4 MigrationTestHelper vs savedstate 1.5.0 (AbstractMethodError)") }
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1") { because("same as serialization-core") }
    }

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.biometric)
    implementation(libs.security.crypto)
    implementation(libs.gson)
    implementation(libs.anthropic.java)
    implementation(libs.exifinterface)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
