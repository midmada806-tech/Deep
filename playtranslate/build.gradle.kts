import java.util.Properties

plugins {
    id("mihon.library")
    kotlin("android")
    kotlin("plugin.serialization")
}

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) localPropsFile.inputStream().use { localProps.load(it) }

// PlayTranslate 3.4.1, merged into Noctis as a library module. Everything that only made sense for a
// standalone app (applicationId, signing, R8 mapping archive, catalog URL verification, ARM-TLS APK check,
// self-updater) lives in the upstream project; Noctis' :app now owns packaging and the launcher entry.
android {
    namespace = "com.playtranslate"

    defaultConfig {
        // Library BuildConfig has no VERSION_* fields (AGP 8+), PlayTranslate reads them for headers / UA strings.
        buildConfigField("String", "VERSION_NAME", "\"3.4.1\"")
        buildConfigField("int", "VERSION_CODE", "23")
        buildConfigField("String", "DEEPL_API_KEY", "\"${localProps.getProperty("deepl.api.key", "")}\"")
        // The standalone self-updater would try to install PlayTranslate's own APK over Noctis. Off here.
        buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("proguard-rules.pro")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDir(rootProject.file("ocr-grouping/assets"))
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/NOTICE.md"
            excludes += "/META-INF/CONTRIBUTORS.md"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/NOTICE"
            // Language data ships in downloadable packs, not in the APK (see upstream app/build.gradle.kts).
            excludes += "models_light/**"
            excludes += "models_full/**"
            // HanLP's bundled data — but NOT opencc4j's data/dictionary/*.txt (Traditional-Chinese converter needs those).
            excludes += "data/dictionary/CoreNatureDictionary*"
            excludes += "data/dictionary/stopwords.txt.bin"
            excludes += "data/dictionary/custom/**"
            excludes += "data/dictionary/organization/**"
            excludes += "data/dictionary/other/**"
            excludes += "data/dictionary/person/**"
            excludes += "data/dictionary/pinyin/**"
            excludes += "data/dictionary/place/**"
            excludes += "data/dictionary/synonym/**"
            excludes += "data/dictionary/tc/**"
            excludes += "data/model/**"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    api(projects.mnn)
    api(projects.bergamot)

    implementation(ptlibs.androidx.core.ktx)
    implementation(ptlibs.androidx.appcompat)
    implementation(ptlibs.material)
    implementation(ptlibs.androidx.constraintlayout)
    implementation(ptlibs.androidx.recyclerview)
    implementation(ptlibs.androidx.lifecycle.viewmodel.ktx)
    implementation(ptlibs.androidx.lifecycle.runtime.ktx)
    implementation(ptlibs.androidx.activity.ktx)
    implementation(ptlibs.androidx.fragment.ktx)
    implementation(ptlibs.kotlinx.coroutines.android)

    // ML Kit
    implementation(ptlibs.mlkit.text.recognition.japanese)
    implementation(ptlibs.mlkit.text.recognition)
    implementation(ptlibs.mlkit.text.recognition.chinese)
    implementation(ptlibs.mlkit.text.recognition.korean)
    implementation(ptlibs.mlkit.text.recognition.devanagari)
    implementation(ptlibs.mlkit.translate)
    implementation(ptlibs.mlkit.language.id)

    // Networking / (de)serialization
    implementation(ptlibs.okhttp)
    implementation(ptlibs.gson)
    implementation(ptlibs.kotlinx.serialization.json)

    // Morphological analysis / stemming / segmentation
    implementation(ptlibs.sudachi)
    implementation(ptlibs.lucene.analyzers.common)
    implementation(ptlibs.komoran)
    implementation(ptlibs.hanlp)
    implementation(ptlibs.opencc4j)

    // PaddleOCR post-processing
    implementation(ptlibs.opencv)

    // Camera tool
    implementation(ptlibs.camerax.core)
    implementation(ptlibs.camerax.camera2)
    implementation(ptlibs.camerax.lifecycle)
    implementation(ptlibs.camerax.view)

    testImplementation(ptlibs.junit)
    testImplementation(ptlibs.robolectric)
    testImplementation(ptlibs.androidx.test.core)

    androidTestImplementation(ptlibs.androidx.test.ext.junit)
    androidTestImplementation(ptlibs.androidx.test.runner)
    androidTestImplementation(ptlibs.androidx.test.rules)
    androidTestImplementation(ptlibs.kotlinx.coroutines.test)
}
