plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.jaredsburrows.license")
}

/**
 * Ships the app already wearing the Calculator disguise: the launcher entry, the icon and the
 * label Android shows in Settings → Apps and in the VPN consent dialog are all the calculator's
 * from the very first launch, with no visit to the settings needed.
 *
 * Same applicationId and signing key as a normal build, so the two APKs install over each other.
 * Enable with: ./gradlew assemblePlaystoreRelease -PDISGUISED=true
 */
val disguisedBuild = (properties["DISGUISED"] as? String)?.toBoolean() ?: false

/** Naming the file after the disguise too — a "ShrimpNG" APK in Downloads defeats the point. */
val apkBaseName = if (disguisedBuild) "FOSS-Calculator" else "ShrimpNG"

android {
    namespace = "com.v2ray.ang"
    compileSdk = 37

    defaultConfig {
        applicationId = "fish.shrimp.ng"
        minSdk = 33
        targetSdk = 37
        versionCode = 117
        versionName = "0.2.4"
        multiDexEnabled = true

        // The <application> label/icon can only be chosen at build time, which is exactly why the
        // disguised build exists: the runtime alias switch cannot reach Settings → Apps or the
        // system VPN consent dialog, and both of those would otherwise say "ShrimpNG".
        manifestPlaceholders["appLabel"] =
            if (disguisedBuild) "@string/shrimp_app_icon_calculator" else "@string/app_name"
        manifestPlaceholders["appIcon"] =
            if (disguisedBuild) "@mipmap/ic_launcher_calc" else "@mipmap/ic_launcher"
        // Exactly one launcher alias may ship enabled, or the app has no launcher entry at all.
        manifestPlaceholders["aliasDefaultEnabled"] = (!disguisedBuild).toString()
        manifestPlaceholders["aliasCalculatorEnabled"] = disguisedBuild.toString()

        buildConfigField("boolean", "DISGUISE_BY_DEFAULT", disguisedBuild.toString())

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        splits {
            abi {
                isEnable = true
                reset()
                if (!abiFilterList.isNullOrEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64",
                        "x86"
                    )
                }
                isUniversalApk = abiFilterList.isNullOrEmpty()
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = rootProject.file("shrimpng-release.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                // No hardcoded fallback — set these in your own (gitignored) gradle.properties
                // or pass as -P/CI secrets (see PROJECT.md). Left unset here, actually assembling
                // a release build fails with AGP's own missing-signing-config error instead of
                // silently falling back to a shared default password.
                providers.gradleProperty("SHRIMPNG_STORE_PASSWORD").orNull?.let { storePassword = it }
                providers.gradleProperty("SHRIMPNG_KEY_ALIAS").orNull?.let { keyAlias = it }
                providers.gradleProperty("SHRIMPNG_KEY_PASSWORD").orNull?.let { keyPassword = it }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    ndkVersion = "29.0.14206865"

    flavorDimensions.add("distribution")
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
            buildConfigField("String", "DISTRIBUTION", "\"F-Droid\"")
        }
        create("playstore") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"Play Store\"")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    applicationVariants.all {
        val variant = this
        val isFdroid = variant.productFlavors.any { it.name == "fdroid" }
        if (isFdroid) {
            val versionCodes =
                mapOf(
                    "armeabi-v7a" to 2, "arm64-v8a" to 1, "x86" to 4, "x86_64" to 3, "universal" to 0
                )

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = output.getFilter("ABI") ?: "universal"
                    output.outputFileName = "${apkBaseName}_${variant.versionName}-fdroid_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (100 * variant.versionCode + versionCodes[abi]!!).plus(5000000)
                    } else {
                        return@forEach
                    }
                }
        } else {
            val versionCodes =
                mapOf("armeabi-v7a" to 4, "arm64-v8a" to 4, "x86" to 4, "x86_64" to 4, "universal" to 4)

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = if (output.getFilter("ABI") != null)
                        output.getFilter("ABI")
                    else
                        "universal"

                    output.outputFileName = "${apkBaseName}_${variant.versionName}_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (1000000 * versionCodes[abi]!!).plus(variant.versionCode)
                    } else {
                        return@forEach
                    }
                }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

dependencies {
    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.preference.ktx)
    implementation(libs.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.fragment)

    // UI Libraries
    implementation(libs.material)
    implementation(libs.toasty)
    implementation(libs.editorkit)
    implementation(libs.flexbox)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Language and Processing Libraries
    implementation(libs.language.base)
    implementation(libs.language.json)

    // Intent and Utility Libraries
    implementation(libs.quickie.foss)
    implementation(libs.core)

    // CameraX — inline live QR preview in the "Add key" sheet (Quickie only exposes its scanner
    // as a whole separate Activity, and only pulls CameraX in as implementation-scoped so it
    // isn't on our compile classpath transitively; declared explicitly here instead)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Multidex Support
    implementation(libs.multidex)

    // Testing Libraries
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.org.mockito.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
