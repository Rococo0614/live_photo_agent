plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val plannerExperiment = providers.gradleProperty("plannerExperiment")
    .orElse("baseline")
    .get()
require(plannerExperiment in setOf("baseline", "p1", "scratchpad", "two_turn")) {
    "plannerExperiment must be one of baseline, p1, scratchpad, two_turn"
}
val plannerEvalOnly = providers.gradleProperty("plannerEvalOnly")
    .orElse("false")
    .get()
require(plannerEvalOnly in setOf("true", "false")) {
    "plannerEvalOnly must be true or false"
}
val agentRuntime = providers.gradleProperty("agentRuntime")
    .orElse("loop")
    .get()
require(agentRuntime in setOf("static", "loop", "dag")) {
    "agentRuntime must be one of static, loop, dag"
}
val localTrackedPreview = providers.gradleProperty("localTrackedPreview")
    .orElse("false")
    .get()
require(localTrackedPreview in setOf("true", "false")) {
    "localTrackedPreview must be true or false"
}
require(localTrackedPreview == "false" || agentRuntime == "dag") {
    "localTrackedPreview requires -PagentRuntime=dag"
}
val qwenModelPack = providers.gradleProperty("qwenModelPack")
    .orElse("instruct")
    .get()
require(qwenModelPack in setOf("instruct", "hybrid")) {
    "qwenModelPack must be one of instruct, hybrid"
}
val qwenThinking = providers.gradleProperty("qwenThinking")
    .orElse("off")
    .get()
require(qwenThinking in setOf("off", "route", "all")) {
    "qwenThinking must be one of off, route, all"
}
val qwenThinkingTokens = providers.gradleProperty("qwenThinkingTokens")
    .orElse("1024")
    .get().toInt()
require(qwenThinkingTokens in 256..2048) {
    "qwenThinkingTokens must be in 256..2048"
}

android {
    namespace = "com.vivo.lpa"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.vivo.lpa"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-mobile-agent"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // One APK source, four reproducible planner arms. Baseline stays the default so a normal
        // build cannot accidentally ship an experimental prompt. Build an arm with e.g.
        // `./gradlew assembleDebug -PplannerExperiment=two_turn`.
        buildConfigField("String", "PLANNER_EXPERIMENT", "\"$plannerExperiment\"")
        // Test APKs may stop after PLAN_FINAL to measure the planner without repeatedly exporting
        // media. Normal builds always default to false.
        buildConfigField("boolean", "PLANNER_EVAL_ONLY", plannerEvalOnly)
        // Runtime architecture is independent from planner prompt experiments. Runtime v2 became
        // the default only after all 31 executors and 30 dynamic binding cases passed on device;
        // `-PagentRuntime=static` remains the explicit rollback path. `dag` is the Runtime v3
        // shadow compiler: it audits a typed graph while Runtime v2 still performs execution.
        buildConfigField("String", "AGENT_RUNTIME", "\"$agentRuntime\"")
        // Experimental language-targeted local preview. Default false; even when enabled, it may
        // only run under Runtime v3 shadow and cannot register a mutation/writeback executor.
        buildConfigField("boolean", "LOCAL_TRACKED_PREVIEW", localTrackedPreview)
        // Native-thinking experiments use a separate model directory and never overwrite the
        // production Instruct-2507 pack. `route` keeps constrained bind classification direct.
        buildConfigField("String", "QWEN_MODEL_PACK", "\"$qwenModelPack\"")
        buildConfigField("String", "QWEN_THINKING", "\"$qwenThinking\"")
        buildConfigField("int", "QWEN_THINKING_TOKENS", qwenThinkingTokens.toString())
        ndk {
            // 两台真机都是 arm64-v8a，只打这一个 ABI，包体减半
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
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
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            if (localTrackedPreview == "true") {
                jniLibs.srcDir("src/localTrackedPreview/jniLibs")
            }
        }
    }

    packaging {
        jniLibs {
            // 两个 SDK 的 .so 若有同名，取第一个，避免打包冲突
            pickFirsts += "**/*.so"
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

// These properties alter generated constants and, for localTrackedPreview, native source sets.
// AGP does not consistently invalidate every downstream task when the same variant is rebuilt
// with a different Gradle property in one worktree. Make the experiment tuple an explicit task
// input so `default -> experiment -> default` cannot reuse incompatible bytecode or packaging.
tasks.configureEach {
    if (name != "clean") {
        inputs.property("lpa.plannerExperiment", plannerExperiment)
        inputs.property("lpa.plannerEvalOnly", plannerEvalOnly)
        inputs.property("lpa.agentRuntime", agentRuntime)
        inputs.property("lpa.localTrackedPreview", localTrackedPreview)
        inputs.property("lpa.qwenModelPack", qwenModelPack)
        inputs.property("lpa.qwenThinking", qwenThinking)
        inputs.property("lpa.qwenThinkingTokens", qwenThinkingTokens)
    }
}

dependencies {
    // vivo SDKs and the LivePhotoSDK support jars downloaded from the internal Maven repo.
    implementation(fileTree("libs") { include("*.aar"); include("*.jar") })

    // AAR 反编译可见依赖: androidx.annotation / collection / core / appcompat
    // videoeditorsdk 的 VMEditor 初始化路径依赖 Gson（AAR 未打包、未声明 POM）
    implementation("com.google.code.gson:gson:2.11.0")
    // mediaeffectsdk 的 TimelineMotionEffect 渲染路径依赖腾讯 PAG（同样未声明）
    implementation("com.tencent.tav:libpag:4.2.41")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
    testImplementation("junit:junit:4.13.2")
    // Android's local JVM stubs throw for org.json methods; the DAG compiler adapter needs a real
    // implementation in unit tests. This dependency is test-only and does not enter the APK.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
