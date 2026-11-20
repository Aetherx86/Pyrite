package dev.aetherx86.pyrite

import dev.aetherx86.pyrite.vm.Assembler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {
    private class Outcome(val status: Int, val out: String, val err: String)

    private val files = ArrayList<File>()

    @AfterTest
    fun cleanUp() {
        files.forEach { it.delete() }
    }

    private fun file(text: String, suffix: String = ".asm"): String {
        val f = File.createTempFile("pyrite", suffix)
        files.add(f)
        f.writeText(text)
        return f.path
    }

    private fun cli(vararg args: String, input: String = ""): Outcome {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val status = Cli(
            ByteArrayInputStream(input.toByteArray()),
            PrintStream(out, true, Charsets.UTF_8),
            PrintStream(err, true, Charsets.UTF_8),
        ).run(arrayOf(*args))
        return Outcome(status, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private val square = "push 6\ncall sq\npush 0\nadd\nhalt\nsq:\nenter 1\nload 0\nload 0\nmul\nret\n"

    @Test
    fun versionMatchesTheBuild() {
        val run = cli("--version")
        assertEquals(0, run.status)
        assertEquals("pyrite $VERSION", run.out.trim())
        // The release workflow checks the tag against the build file, so the two must agree.
        val declared = File("build.gradle.kts").readLines().first { it.startsWith("version = ") }
        assertEquals("version = \"$VERSION\"", declared)
    }

    @Test
    fun vmRunsOnBothBackendsAndReportsJson() {
        val path = file(square)
        assertEquals("36", cli("vm", path).out.trim())
        assertEquals("36", cli("vm", path, "--jit").out.trim())

        val json = cli("vm", path, "--json").out.trim()
        assertTrue(json.startsWith("""{"ok":true,"result":36,"backend":"interpreter","codeBytes":19,"elapsedNanos":"""), json)
        assertTrue(""""executed":9}""" in json, json)

        val optimized = cli("vm", path, "--jit", "--optimize", "--json").out.trim()
        assertTrue(""""backend":"jit","codeBytes":15""" in optimized, optimized)
        assertTrue(""""optimization":{"bytesBefore":19,"bytesAfter":15,""" in optimized, optimized)
    }

    @Test
    fun failuresAreReportedAsJson() {
        val trap = cli("vm", file("push 1\npush 0\ndiv\nhalt"), "--json")
        assertEquals(1, trap.status)
        assertEquals("""{"ok":false,"error":"trap","trap":"DIVIDE_BY_ZERO"}""", trap.out.trim())

        val fuel = cli("vm", file("top:\njmp top"), "--fuel", "50", "--json")
        assertEquals("""{"ok":false,"error":"trap","trap":"OUT_OF_FUEL"}""", fuel.out.trim())

        val asm = cli("vm", file("push 1\nbogus\nhalt"), "--json")
        assertEquals(1, asm.status)
        assertEquals(
            """{"ok":false,"error":"assembly","line":2,"message":"line 2: unknown instruction 'bogus'"}""",
            asm.out.trim(),
        )

        val verify = cli("verify", file("push 1\nret"), "--json")
        assertEquals(1, verify.status)
        assertEquals(
            """{"ok":false,"error":"verify","offset":3,"message":"at offset 3: ret outside a function"}""",
            verify.out.trim(),
        )

        // Without --json the message goes to standard error.
        val plain = cli("vm", file("push 1\npush 0\nrem\nhalt"))
        assertEquals(1, plain.status)
        assertEquals("error: DIVIDE_BY_ZERO", plain.err.trim())
    }

    @Test
    fun verifyReportsWhatWasProved() {
        val path = file(square)
        assertEquals(
            """{"ok":true,"codeBytes":19,"instructions":10,"reachableInstructions":10,"maxStack":2,""" +
                """"functions":[{"entry":11,"argc":1,"locals":1,"maxStack":2}]}""",
            cli("verify", path, "--json").out.trim(),
        )
        val text = cli("verify", path).out
        assertTrue("function at 11: 1 argument(s), 1 local(s), max stack 2" in text, text)
    }

    @Test
    fun disasmOutputReassembles() {
        val path = file(square)
        val run = cli("disasm", path)
        assertEquals(0, run.status)
        assertContentEquals(Assembler.assemble(square), Assembler.assemble(run.out))

        val optimized = cli("disasm", path, "--optimize").out
        assertTrue(optimized.startsWith("; optimized: 19 -> 15 bytes, 10 -> 8 instructions\n"), optimized)
        assertEquals(15, Assembler.assemble(optimized).size)
    }

    @Test
    fun benchComparesTheBackends() {
        val loop = """
            push 2000
            store 0
        top:
            load 0
            jz done
            load 1
            load 0
            call sq
            add
            store 1
            load 0
            push 1
            sub
            store 0
            jmp top
        done:
            load 1
            halt
        sq:
            enter 1
            load 0
            load 0
            mul
            ret
        """
        val run = cli("bench", file(loop), "--runs", "3", "--json")
        assertEquals(0, run.status, run.err)
        val json = run.out.trim()
        assertTrue(json.startsWith("""{"ok":true,"runs":3,"""), json)
        assertTrue(""""agree":true""" in json, json)
        // The sum of squares 1..2000, wrapped to 32 bits.
        val expected = (1L..2000L).sumOf { it * it }.toInt()
        assertTrue(""""interpreter":{"result":$expected,"minNanos":""" in json, json)
        assertTrue(""""jit":{"result":$expected,"minNanos":""" in json, json)
        assertTrue(""""compileNanos":""" in json, json)

        val text = cli("bench", file(loop), "--runs", "2").out
        assertTrue(text.startsWith("interpreter  result $expected  min "), text)
        assertTrue("\njit          result $expected  min " in text, text)
        assertTrue("\nspeedup      " in text, text)
    }

    @Test
    fun benchAgreesOnTrapsAndSkipsTheJitWhenFuelRunsOut() {
        val trap = cli("bench", file("push 1\npush 0\ndiv\nhalt"), "--json")
        assertEquals(0, trap.status)
        assertTrue(""""interpreter":{"trap":"DIVIDE_BY_ZERO","executed":3}""" in trap.out, trap.out)
        assertTrue(""""jit":{"trap":"DIVIDE_BY_ZERO","compileNanos":""" in trap.out, trap.out)

        val spin = cli("bench", file("top:\njmp top"), "--fuel", "1000", "--json")
        assertEquals(0, spin.status)
        assertTrue(""""jit":{"unavailable":"the interpreter ran out of fuel"}""" in spin.out, spin.out)
    }

    @Test
    fun badCommandLinesExitWithStatusTwo() {
        val path = file(square)
        val cases = listOf(
            arrayOf("vm", path, "--bogus") to "unknown option '--bogus'",
            arrayOf("vm", path, "--fuel") to "--fuel needs a value",
            arrayOf("vm", path, "--fuel", "lots") to "--fuel needs a number",
            arrayOf("vm", path, "--fuel", "-1") to "--fuel needs a number",
            arrayOf("vm", path, "--jit", "--fuel", "10") to "--fuel only applies to the interpreter",
            arrayOf("bench", path, "--runs", "0") to "--runs needs a number",
            arrayOf("bf", path, "--tape", "0") to "--tape needs a number",
            arrayOf("launch", path) to "unknown command 'launch'",
        )
        for ((args, message) in cases) {
            val run = cli(*args)
            assertEquals(2, run.status, args.joinToString(" "))
            assertTrue(message in run.err, "expected '$message' in '${run.err}'")
        }
        assertEquals(0, cli().status)
        assertEquals(2, cli("vm").status)
    }

    @Test
    fun brainfuckStillRunsThroughTheCli() {
        val cat = file(",.,.,.,.,.", ".b")
        for (extra in listOf(emptyArray(), arrayOf("--interpret"), arrayOf("--fuel", "1000"))) {
            val run = cli("bf", cat, *extra, input = "hello")
            assertEquals(0, run.status, run.err)
            assertEquals("hello", run.out)
        }
        val spin = cli("bf", file("+[]", ".b"), "--fuel", "100")
        assertEquals(1, spin.status)
        assertTrue("fuel" in spin.err, spin.err)
    }

    @Test
    fun jsonEscapesEverythingItMust() {
        assertEquals(
            "{\"a\":\"q\\\"b\\\\s\\n\\t\\u0001\\u2028\",\"n\":[1,-2,null,true],\"m\":{}}",
            Json.write(mapOf("a" to "q\"b\\s\n\t\u0001\u2028", "n" to listOf(1, -2L, null, true), "m" to emptyMap<String, Int>())),
        )
    }
}
