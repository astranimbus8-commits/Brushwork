plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from environment variables (set by CI from repository secrets, or
// locally). Without them the release APK is signed with the debug key so it still installs.
val keystoreFile: String? = System.getenv("BW_KEYSTORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }

// The versionCode: the workflow's run number (BW_VERSION_CODE), 1 locally.
val releaseCode: Int = System.getenv("BW_VERSION_CODE")?.toIntOrNull() ?: 1

// v1.9 A8 (R16): an APK that does not install over the last release makes the user uninstall the
// app, and uninstalling deletes the artworks. So when the workflow asks for it (BW_RELEASE_GUARD=true:
// the build of a release tag only, never a push to main or a pull request), a release build refuses
// to run without the release keystore, or with a versionCode not above the last release tag's
// (BW_LAST_VERSION_CODE: the run number of that tag's build, which the workflow looks up). Local
// builds and unguarded CI builds keep the debug-key fallback.
val releaseGuard = tasks.register("checkReleaseGuards") {
    group = "verification"
    description = "Refuses a CI release build without the release keystore or with a versionCode not above the last release's."
    val guarded = System.getenv("BW_RELEASE_GUARD") == "true"
    val hasKey = keystoreFile != null
    val code = releaseCode
    val last = System.getenv("BW_LAST_VERSION_CODE")?.toIntOrNull()
    doLast {
        if (!guarded) {
            if (!hasKey) logger.warn("No release keystore (BW_KEYSTORE_FILE): this APK is signed with the debug key and does not install over a release.")
            return@doLast
        }
        if (!hasKey) {
            throw GradleException(
                "No release keystore (BW_KEYSTORE_FILE): a debug-signed APK does not install over the release, and uninstalling " +
                    "deletes the artworks. Set the KEYSTORE_BASE64 secret.",
            )
        }
        if (last != null && code <= last) {
            throw GradleException(
                "versionCode $code is not above $last, the last release's: Android refuses to install it over that release.",
            )
        }
        logger.lifecycle("Release guards: the release keystore is set; versionCode $code (the last release's: ${last ?: "none"}).")
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(releaseGuard) }

// v1.9 (LA3): the CI shard of a test class, by its fully qualified name before the first '$', so a
// nested class runs with its outer class. CiShardFilterTest checks this exact line.
fun ciShardOf(className: String, shards: Int): Int = (className.substringBefore('$').hashCode() and 0x7fffffff) % shards

android {
    namespace = "com.brushwork.paint"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.brushwork.paint"
        minSdk {
            version = release(26)
        }
        targetSdk {
            version = release(37)
        }
        versionCode = releaseCode
        // Phones are ARM: skip the x86 builds of the native (LiteRT) libraries to keep the APK small.
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
        // Release builds from a tag (v1.2.3) are named after it; other CI builds get 1.x.<run>.
        versionName = System.getenv("BW_VERSION_NAME")?.removePrefix("v")?.takeIf { it.isNotBlank() }
            ?: ("1.1." + (System.getenv("BW_VERSION_CODE") ?: "0"))
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("BW_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BW_KEY_ALIAS")
                keyPassword = System.getenv("BW_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (keystoreFile != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        noCompress += "tflite"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // ~1450 Robolectric tests with real Skia need more than the default 512 MB.
                it.maxHeapSize = "1536m"
                // Each test class with its own instrumentedPackages gets a Robolectric sandbox, and
                // core/Parallel's threads keep evicted sandboxes reachable: one JVM for the whole
                // suite (~2650 tests) runs out of memory, so start a fresh one every 40 classes.
                // v1.10 lead (CI fix after A10's merge): each sandbox also unpacks Robolectric's native
                // runtime (about 200 MB of fonts, ICU data and the library) into a temporary folder of
                // its own, deleted only when its JVM exits. A JVM of 40 classes held about 8 GB, the two
                // JVMs of a CI shard about 16 GB, and unit-full shard 1 filled the runner's disk until the
                // runner died. On CI a fresh JVM every 12 classes keeps both under about 5 GB.
                it.forkEvery = if (System.getenv("CI") != null) 12 else 40
                // v1.10 lead (CI fix at A8's merge): the ubuntu-26.04 runners keep /tmp in a small
                // tmpfs with a quota, and every fork above unpacks Robolectric's native runtime (about
                // 200 MB) there, beside each test's app folders: the shards of the first run on that
                // image stopped with "Disk quota exceeded". On a GitHub runner the test JVMs keep their
                // temporary files on the runner's disk ($RUNNER_TEMP) instead; locally nothing changes.
                System.getenv("RUNNER_TEMP")?.takeIf { t -> t.isNotBlank() }?.let { runnerTemp ->
                    val testTmp = File(runnerTemp, "test-jvm-tmp")
                    it.doFirst { testTmp.mkdirs() }
                    it.systemProperty("java.io.tmpdir", testTmp.absolutePath)
                }
                // v1.8 (CI2): on CI the non-timing tests run in two JVMs, and the timing tests
                // (@Category(Timing), every class that reads a clock) run alone in a second, serial
                // step (-PtimingOnly), so two JVMs sharing cores never skew a budget. Locally nothing
                // changes: one JVM, every test.
                val ci = System.getenv("CI") != null
                val timingOnly = project.hasProperty("timingOnly")
                // v1.9 (design 2.2, 5.5): -PsoakOnly runs SoakHarnessTest alone, serially (the
                // nightly soak job), whatever its category. v1.10 (design 6.5, the A10 merge): with
                // Soak500LayersTest, which runs its NIGHTLY plan there (bw.testSet "soak": about 500
                // layers for 20 minutes at FULL, then the CLASSIC replay).
                val soakOnly = project.hasProperty("soakOnly")
                // v1.9 gate 1 (LA1, design 2.7): -Pbench runs the P0 proxy bench (qa19/P0ProxyBench)
                // alone, serially. Every CI run leaves it out, so the CI counts never include it;
                // locally it runs with the rest and skips itself (bw.testSet is not "bench").
                val bench = project.hasProperty("bench")
                // v1.10 F1 (design 3.3, 6.5): -PunitFull runs the suite as the unit-full job does, at
                // -PengineMode=full unless another level above classic is given (ChaosResidency), without
                // the Timing, @Category(ClassicOnly) and @Category(EngineGolden) tests and without the
                // classes listed in src/test/unit-full-backlog.txt (below). Locally too.
                val unitFull = project.hasProperty("unitFull")
                if (unitFull && (timingOnly || soakOnly || bench)) {
                    throw GradleException("-PunitFull runs the main suite; it does not go with -PtimingOnly, -PsoakOnly or -Pbench")
                }
                it.maxParallelForks = if (ci && !timingOnly && !soakOnly && !bench) 2 else 1
                it.useJUnit {
                    if (timingOnly) includeCategories("com.brushwork.paint.testing.Timing")
                    else if (unitFull) {
                        excludeCategories(
                            "com.brushwork.paint.testing.Timing",
                            "com.brushwork.paint.testing.ClassicOnly",
                            "com.brushwork.paint.testing.EngineGolden",
                        )
                    } else if (ci && !soakOnly && !bench) excludeCategories("com.brushwork.paint.testing.Timing")
                }
                if (soakOnly) {
                    it.filter.includeTestsMatching("*.SoakHarnessTest")
                    it.filter.includeTestsMatching("*.Soak500LayersTest")
                    it.filter.isFailOnNoMatchingTests = false
                }
                if (bench) {
                    it.filter.includeTestsMatching("*.P0ProxyBench")
                    it.filter.isFailOnNoMatchingTests = false
                } else if (ci) {
                    it.exclude("**/qa19/P0ProxyBench*")
                }
                it.inputs.property("bench", bench.toString())
                // v1.9 (LA3, design 2.2): -PtestShard=<i> -PtestShards=<n> runs only the classes of
                // shard i of n (ciShardOf), so CI runs the non-timing tests as parallel jobs.
                val testShard = project.findProperty("testShard")?.toString()?.toIntOrNull()
                val testShards = project.findProperty("testShards")?.toString()?.toIntOrNull()
                if ((testShard == null) != (testShards == null) || (testShards != null && (testShards < 1 || testShard!! !in 0 until testShards))) {
                    throw GradleException("-PtestShard=<i> and -PtestShards=<n> go together, with 0 <= i < n")
                }
                if (testShard != null && testShards != null) {
                    it.exclude { e ->
                        !e.isDirectory && e.name.endsWith(".class") &&
                            ciShardOf(e.relativePath.pathString.removeSuffix(".class").replace('/', '.'), testShards) != testShard
                    }
                }
                // Task inputs, so the build cache never hands back a run made with other settings
                // (the shard filter above is a closure, which Gradle cannot fingerprint).
                it.inputs.property("testShard", testShard?.toString() ?: "all")
                it.inputs.property("testShards", testShards?.toString() ?: "all")
                it.systemProperty("bw.testForks", it.maxParallelForks.toString())
                it.systemProperty("bw.testSet", if (bench) "bench" else if (soakOnly) "soak" else if (timingOnly) "timing" else if (ci) "main" else "all")
                // v1.10 Gate 0 (design 3.1): -PengineMode=classic|tiles|full and -PparkChaos reach the
                // unit-test JVMs only, as brushwork.engineMode and brushwork.parkChaos=true; only
                // EngineConfig.testMode() (F1) reads them. Without -PengineMode nothing is set, so
                // EngineConfig keeps its own default. Both are task inputs, like the shard above.
                val engineMode = project.findProperty("engineMode")?.toString()?.trim()?.lowercase() ?: if (unitFull) "full" else null
                if (engineMode != null && engineMode !in setOf("classic", "tiles", "full")) {
                    throw GradleException("-PengineMode is classic, tiles or full (got \"$engineMode\")")
                }
                if (unitFull && engineMode == "classic") throw GradleException("-PunitFull runs at -PengineMode=full or tiles, not classic")
                val parkChaos = project.findProperty("parkChaos")?.toString()?.trim()?.lowercase().let { p -> p != null && p != "false" }
                if (engineMode != null) it.systemProperty("brushwork.engineMode", engineMode)
                if (parkChaos) it.systemProperty("brushwork.parkChaos", "true")
                it.inputs.property("engineMode", engineMode ?: "unset")
                it.inputs.property("parkChaos", parkChaos.toString())
                // v1.10 F1 (design 3.3): src/test/unit-full-backlog.txt lists the classes -PunitFull leaves
                // out until their owner area fixes them, one per line: "<fully.qualified.Class> <A1..A11>
                // <reason>"; '#' starts a comment line. A nested class goes with its outer class, as in
                // the shard filter. The file and the switch are task inputs.
                val unitFullBacklog = project.file("src/test/unit-full-backlog.txt")
                it.inputs.property("unitFull", unitFull.toString())
                it.inputs.file(unitFullBacklog).withPropertyName("unitFullBacklog")
                if (unitFull) {
                    val entry = Regex("""([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+)\s+(A[1-9]|A1[01])\s+\S.*""")
                    val backlog = unitFullBacklog.readLines().mapIndexedNotNull { i, raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) {
                            null
                        } else {
                            entry.matchEntire(line)?.groupValues?.get(1)
                                ?: throw GradleException("src/test/unit-full-backlog.txt:${i + 1}: not \"<fully.qualified.Class> <A1..A11> <reason>\": $line")
                        }
                    }.toSet()
                    if (backlog.isNotEmpty()) {
                        it.exclude { e ->
                            !e.isDirectory && e.name.endsWith(".class") &&
                                e.relativePath.pathString.removeSuffix(".class").replace('/', '.').substringBefore('$') in backlog
                        }
                    }
                }
                // A failure prints its whole message (a timing guard's every measurement), so a CI
                // log says why without the reports.
                it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                // v1.7: the platform android.graphics.PathIterator (Pathfinder's reader on API 34,
                // the SDK Robolectric runs) allocates through ShadowVMRuntime.newNonMovableArray,
                // which reads a DirectByteBuffer's address by reflection.
                it.jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.exifinterface)
    // v1.7: Path.op results are read back with androidx.graphics.path.PathIterator (Pathfinder); the
    // version Compose already resolves.
    implementation(libs.androidx.graphics.path)
    implementation(libs.zxing.core)
    implementation(libs.mlkit.subject.segmentation)
    implementation(libs.litert)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}