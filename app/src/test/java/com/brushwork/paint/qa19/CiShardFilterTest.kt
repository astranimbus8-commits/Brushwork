package com.brushwork.paint.qa19

import com.brushwork.paint.testing.Timing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Modifier

/**
 * v1.9 F0 (LA3, design 2.2 and 5.5): the CI shard filter of `app/build.gradle.kts`
 * (`-PtestShard=<i> -PtestShards=<n>`) runs every test class of the module in exactly one shard,
 * a nested class in its outer class's shard, and splits the non-timing tests about evenly.
 *
 * The filter is a Gradle exclude spec over the compiled test classes; this test walks the same
 * class files (the folder this class was loaded from), names them as the spec does
 * ([classNameOf]) and shards them with [ciShardOf], whose line [SHARD_FUNCTION] must be the build
 * script's own, character for character.
 */
class CiShardFilterTest {

    /** The module's `build.gradle.kts`, from the working directory (Gradle runs tests in `app/`). */
    private fun buildScript(): File {
        val user = File(System.getProperty("user.dir") ?: ".").absoluteFile
        return listOf(File(user, "build.gradle.kts"), File(user, "app/build.gradle.kts")).firstOrNull { it.isFile && it.readText().contains("testOptions") }
            ?: throw AssertionError("app/build.gradle.kts not found from $user")
    }

    /** The folder of the compiled test classes, which Gradle's filter scans. */
    private fun classesDir(): File {
        val location = javaClass.protectionDomain?.codeSource?.location ?: throw AssertionError("no code source for ${javaClass.name}")
        val dir = File(location.toURI())
        assertTrue("the test classes are a folder: $dir", dir.isDirectory)
        return dir
    }

    /** Every class file of the module's tests, by its path under [classesDir] with '/' separators. */
    private fun classFiles(): List<String> {
        val root = classesDir()
        return root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
            .map { it.relativeTo(root).path.replace('\\', '/') }.sorted().toList()
    }

    /** A test class (what Gradle's JUnit 4 detection runs) found in the class files. */
    private data class TestClass(val name: String, val tests: Int, val timing: Boolean)

    /** The test classes: concrete, with `@RunWith` or a public `@Test` method (inherited ones too). */
    private fun testClasses(paths: List<String>): List<TestClass> {
        val loader = javaClass.classLoader
        val out = ArrayList<TestClass>()
        for (p in paths) {
            val c = try {
                Class.forName(classNameOf(p), false, loader)
            } catch (e: LinkageError) {
                continue
            }
            if (c.isInterface || c.isAnnotation || Modifier.isAbstract(c.modifiers) || c.isAnonymousClass || c.isLocalClass) continue
            val tests = try {
                c.methods.count { it.isAnnotationPresent(Test::class.java) }
            } catch (e: LinkageError) {
                0
            }
            if (tests == 0 && !c.isAnnotationPresent(RunWith::class.java)) continue
            val timing = c.getAnnotation(Category::class.java)?.value?.any { it == Timing::class } == true
            out += TestClass(c.name, tests, timing)
        }
        return out
    }

    @Test
    fun theFilterIsTheBuildScriptsOwn() {
        val script = buildScript().readText().replace("\r\n", "\n")
        val line = script.lines().singleOrNull { it.startsWith("fun ciShardOf(") }
        assertEquals("the build's shard function", SHARD_FUNCTION, line)
        assertTrue("the build names a class as classNameOf does", script.contains(SHARD_SPEC))
        // The shard is part of the test task's cache key (LA3): a cached run of one shard is never
        // handed to the other.
        assertTrue(script.contains("it.inputs.property(\"testShard\","))
        assertTrue(script.contains("it.inputs.property(\"testShards\","))
    }

    @Test
    fun everyClassRunsInExactlyOneShard() {
        val paths = classFiles()
        assertTrue("the module's test classes were found (${paths.size} files)", paths.size > 1000)
        for (n in listOf(2, 3)) {
            for (p in paths) {
                val shards = (0 until n).filter { i -> !excludedFrom(p, i, n) }
                assertEquals("$p in shards $shards of $n", 1, shards.size)
            }
        }
    }

    @Test
    fun aNestedClassRunsWithItsOuterClass() {
        val paths = classFiles()
        val nested = paths.filter { '$' in it }
        assertTrue("nested classes were found", nested.size > 100)
        for (n in listOf(2, 3)) {
            for (p in nested) {
                val outer = p.substringBefore('$') + ".class"
                assertEquals("$p with $outer of $n", ciShardOf(classNameOf(outer), n), ciShardOf(classNameOf(p), n))
                for (i in 0 until n) assertEquals("$p in shard $i of $n as $outer", excludedFrom(outer, i, n), excludedFrom(p, i, n))
            }
        }
    }

    @Test
    fun theShardsHoldAboutAsManyTests() {
        val classes = testClasses(classFiles()).filter { !it.timing }
        val total = classes.sumOf { it.tests }
        assertTrue("the non-timing tests were found ($total in ${classes.size} classes)", total > 3000)
        for (n in listOf(2, 3)) {
            val per = (0 until n).map { i -> classes.filter { ciShardOf(it.name, n) == i }.sumOf { it.tests } }
            assertEquals(total, per.sum())
            println("[ci shards] $n shards: $per of $total non-timing tests in ${classes.size} classes")
            for ((i, count) in per.withIndex()) {
                val share = count.toDouble() / total
                assertTrue("shard $i of $n holds $count of $total tests ($per)", share >= 1.0 / n - 0.15 && share <= 1.0 / n + 0.15)
            }
        }
    }

    @Test
    fun theFunctionSplitsNamesAsTheDesignSays() {
        assertEquals(ciShardOf("a.B", 2), ciShardOf("a.B\$C\$1", 2))
        assertEquals(("a.B".hashCode() and 0x7fffffff) % 3, ciShardOf("a.B\$Inner", 3))
        // A negative hash code still gives a shard in range.
        val negative = generateSequence(0) { it + 1 }.map { "x.C$it" }.first { it.hashCode() < 0 }
        assertTrue(ciShardOf(negative, 2) in 0..1)
    }

    companion object {
        /** The line of `app/build.gradle.kts` that [ciShardOf] repeats. */
        const val SHARD_FUNCTION = "fun ciShardOf(className: String, shards: Int): Int = (className.substringBefore('$').hashCode() and 0x7fffffff) % shards"

        /** How the build's exclude spec names a class file. */
        const val SHARD_SPEC = "ciShardOf(e.relativePath.pathString.removeSuffix(\".class\").replace('/', '.'), testShards) != testShard"

        /** The build script's function ([SHARD_FUNCTION]). */
        fun ciShardOf(className: String, shards: Int): Int = (className.substringBefore('$').hashCode() and 0x7fffffff) % shards

        /** A class file's path under the classes folder as the build's spec names it ([SHARD_SPEC]). */
        fun classNameOf(path: String): String = path.removeSuffix(".class").replace('/', '.')

        /** Whether the build's exclude spec leaves [path] out of shard [i] of [n]. */
        fun excludedFrom(path: String, i: Int, n: Int): Boolean = path.endsWith(".class") && ciShardOf(classNameOf(path), n) != i
    }
}
