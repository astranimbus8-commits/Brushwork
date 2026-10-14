plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing comes from environment variables (set by CI from repository secrets, or
// locally). Without them the release APK is signed with the debug key so it still installs.
val keystoreFile: String? = System.getenv("BW_KEYSTORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }

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
        versionCode = System.getenv("BW_VERSION_CODE")?.toIntOrNull() ?: 1
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
                it.forkEvery = 40
                // v1.8 (CI2): on CI the non-timing tests run in two JVMs, and the timing tests
                // (@Category(Timing), every class that reads a clock) run alone in a second, serial
                // step (-PtimingOnly), so two JVMs sharing cores never skew a budget. Locally nothing
                // changes: one JVM, every test.
                val ci = System.getenv("CI") != null
                val timingOnly = project.hasProperty("timingOnly")
                // v1.9 (design 2.2, 5.5): -PsoakOnly runs SoakHarnessTest alone, serially (the
                // nightly soak job), whatever its category.
                val soakOnly = project.hasProperty("soakOnly")
                // v1.9 gate 1 (LA1, design 2.7): -Pbench runs the P0 proxy bench (qa19/P0ProxyBench)
                // alone, serially. Every CI run leaves it out, so the CI counts never include it;
                // locally it runs with the rest and skips itself (bw.testSet is not "bench").
                val bench = project.hasProperty("bench")
                it.maxParallelForks = if (ci && !timingOnly && !soakOnly && !bench) 2 else 1
                it.useJUnit {
                    if (timingOnly) includeCategories("com.brushwork.paint.testing.Timing")
                    else if (ci && !soakOnly && !bench) excludeCategories("com.brushwork.paint.testing.Timing")
                }
                if (soakOnly) {
                    it.filter.includeTestsMatching("*.SoakHarnessTest")
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