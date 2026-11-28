import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val appCustomization = Properties().apply {
    val f = rootProject.file("app/provider-customization.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun customizationValue(key: String): String =
    (appCustomization.getProperty(key) ?: "").replace("\"", "\\\"")

android {
    namespace = "com.openminis.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openminis.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 93
        versionName = "2.0-final-2026-11-28-ubuntu24.04-minis"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("boolean", "DEV_TOOLS", "false")

        buildConfigField(
            "String",
            "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT",
            "\"${customizationValue("ANTHROPIC_OAUTH_IDENTIFIER_PROMPT")}\""
        )

        buildConfigField("String", "RELEASE_REPO", "\"OpenMinis/OpenMinis\"")
        buildConfigField("String", "UPDATE_CHANNEL", "\"stable\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "DEV_TOOLS", "true")
        }
        create("perf") {
            initWith(getByName("debug"))
            isDebuggable = false
            isProfileable = true
            matchingFallbacks += listOf("debug")
            buildConfigField("boolean", "DEV_TOOLS", "true")
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            ndk {
                debugSymbolLevel = "FULL"
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("boolean", "SELF_UPDATE", "true")
            buildConfigField("boolean", "AUTO_UPDATE_CHECK", "true")
            buildConfigField("boolean", "IN_APP_INSTALL", "true")
        }
        create("store") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE", "true")
            buildConfigField("boolean", "AUTO_UPDATE_CHECK", "false")
            buildConfigField("boolean", "IN_APP_INSTALL", "true")
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

    androidResources {
        noCompress += listOf("tar.gz", "proot-aarch64")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.maxHeapSize = "900m"
            it.jvmArgs("-XX:TieredStopAtLevel=1")
            it.forkEvery = 120
            it.systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public")
            it.systemProperty("robolectric.dependency.repo.id", "aliyun")
        }
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
        getByName("perf") {
            assets.srcDirs("$projectDir/src/debug/assets")
        }
    }

    lint {
        checkReleaseBuilds = false
        disable += "NonNullableMutableLiveData"
    }
}

val storeDevVariants = providers.gradleProperty("minis.storeDev").map { it.toBoolean() }.getOrElse(false)
androidComponents {
    beforeVariants(selector().withFlavor("distribution" to "store")) { variant ->
        if (variant.buildType != "release" && !storeDevVariants) variant.enable = false
    }
}
for (buildType in listOf("debug", "release", "perf")) {
    val cap = buildType.replaceFirstChar { it.uppercase() }
    val copyLegacy = tasks.register<Copy>("copyGithub${cap}ApkToLegacyPath") {
        from(layout.buildDirectory.dir("outputs/apk/github/$buildType")) {
            include("app-github-$buildType.apk")
            rename { "app-$buildType.apk" }
        }
        into(layout.buildDirectory.dir("outputs/apk/$buildType"))
    }
    tasks.matching { it.name == "assembleGithub$cap" || it.name == "packageGithub$cap" }
        .configureEach { finalizedBy(copyLegacy) }
    tasks.register("install$cap") {
        group = "install"
        description = "Installs the github $buildType build (alias of installGithub$cap)."
        dependsOn("installGithub$cap")
    }
}
afterEvaluate {
    tasks.named("assembleRelease") { setDependsOn(listOf("assembleGithubRelease")) }
    val githubUnitTests = tasks.named<Test>("testGithubDebugUnitTest")
    tasks.register<Test>("testDebugUnitTest") {
        group = "verification"
        description = "Runs the githubDebug unit tests (pre-flavor name; supports --tests)."
        testClassesDirs = githubUnitTests.get().testClassesDirs
        classpath = githubUnitTests.get().classpath
        jvmArgs(githubUnitTests.get().jvmArgs ?: emptyList<String>())
        systemProperties(githubUnitTests.get().systemProperties)
        maxHeapSize = githubUnitTests.get().maxHeapSize
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val copyBashismRules by tasks.registering(Copy::class) {
    from(rootProject.file("../shared/bashism")) {
        include("bashism_rules.json", "bashism_test_vectors.json")
    }
    into(layout.projectDirectory.dir("src/main/assets/bashism"))
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(copyBashismRules) }
tasks.named("preBuild") { dependsOn(copyBashismRules) }

val stageDebugSkillAssets by tasks.registering(Exec::class) {
    val script = rootProject.file("../../scripts/gen_debug_skill_android.sh")
    val skillDir = rootProject.file("../../.claude/skills/debug-server")
    onlyIf { script.exists() }
    if (skillDir.isDirectory) inputs.dir(skillDir)
    if (script.isFile) inputs.file(script)
    outputs.dir(layout.projectDirectory.dir("src/debug/assets/debug-skill"))
    commandLine("bash", script.absolutePath)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") && (it.name.contains("Debug") || it.name.contains("Perf")) }
    .configureEach { dependsOn(stageDebugSkillAssets) }

abstract class WriteBuildInfoTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val repoDir: DirectoryProperty

    @TaskAction
    fun write() {
        val file = outputDir.file("build_info/build_time.txt").get().asFile
        file.parentFile.mkdirs()
        file.writeText(System.currentTimeMillis().toString())
        fun git(vararg args: String): String = runCatching {
            val p = ProcessBuilder(listOf("git") + args).directory(repoDir.get().asFile).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
        }.getOrDefault("")
        val head = git("rev-parse", "--short=9", "HEAD").ifEmpty { "unknown" }
        val dirty = git("status", "--porcelain", "--untracked-files=no", "--", "src/android").isNotEmpty()
        outputDir.file("build_info/commit.txt").get().asFile.writeText(if (dirty) "$head-dirty" else head)
    }
}
androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<WriteBuildInfoTask>("write${cap}BuildInfo") {
            outputs.upToDateWhen { false }
            repoDir.set(rootProject.layout.projectDirectory.dir("../.."))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, WriteBuildInfoTask::outputDir)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material3.adaptive:adaptive:1.2.0")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.2.0")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation:1.2.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.github.helloooideeeeea:RealTimeCutVADLibraryForAndroid:1.0.5@aar")

    implementation(group = "", name = "rclone", ext = "aar")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("com.mikepenz:multiplatform-markdown-renderer-android:0.33.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3-android:0.33.0")

    implementation("androidx.browser:browser:1.8.0")

    implementation("androidx.webkit:webkit:1.12.1")

    implementation("sh.calvin.reorderable:reorderable:2.4.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("ch.acra:acra-core:5.12.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20231013")
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")
    testImplementation("org.robolectric:robolectric:4.17")

    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("junit:junit:4.13.2")
}
