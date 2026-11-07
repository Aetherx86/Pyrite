package dev.aetherx86.pyrite.bf

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BfTest {
    private data class Run(val output: String, val error: Class<*>?, val tape: ByteArray?, val pointer: Int?)

    private enum class Backend { REFERENCE, UNOPTIMIZED_JIT, INTERPRETER, JIT }

    private fun execute(
        backend: Backend,
        source: String,
        input: String = "",
        tapeSize: Int = 4096,
        eof: EofBehavior = EofBehavior.UNCHANGED,
        fuel: Long = Long.MAX_VALUE,
    ): Run {
        val out = ByteArrayOutputStream()
        val io = BfIo(ByteArrayInputStream(input.toByteArray(Charsets.ISO_8859_1)), out, eof, fuel)
        val tape = ByteArray(tapeSize)
        return try {
            val pointer = when (backend) {
                Backend.REFERENCE -> ReferenceInterpreter.run(source, tape, io, fuel)
                Backend.UNOPTIMIZED_JIT ->
                    BfJit.compile(Parser.parse(source), metered = fuel != Long.MAX_VALUE).run(tape, io)
                Backend.INTERPRETER ->
                    FlatInterpreter(Optimizer.optimize(Parser.parse(source))).run(tape, io)
                Backend.JIT ->
                    BfJit.compile(Optimizer.optimize(Parser.parse(source)), metered = fuel != Long.MAX_VALUE)
                        .run(tape, io)
            }
            Run(out.toString(Charsets.ISO_8859_1), null, tape, pointer)
        } catch (e: TapeOutOfBounds) {
            // The tape is unspecified after a failure, because a multiply loop fails as a whole.
            Run(out.toString(Charsets.ISO_8859_1), TapeOutOfBounds::class.java, null, null)
        } catch (e: OutOfFuel) {
            Run(out.toString(Charsets.ISO_8859_1), OutOfFuel::class.java, null, null)
        }
    }

    private fun assertSameEverywhere(
        source: String,
        input: String = "",
        tapeSize: Int = 4096,
        eof: EofBehavior = EofBehavior.UNCHANGED,
    ) {
        val reference = execute(Backend.REFERENCE, source, input, tapeSize, eof)
        for (backend in listOf(Backend.UNOPTIMIZED_JIT, Backend.INTERPRETER, Backend.JIT)) {
            val got = execute(backend, source, input, tapeSize, eof)
            assertEquals(reference.output, got.output, "$backend output for $source")
            assertEquals(reference.error, got.error, "$backend error for $source")
            assertEquals(reference.pointer, got.pointer, "$backend pointer for $source")
            if (reference.tape != null) {
                assertContentEquals(reference.tape, got.tape, "$backend tape for $source")
            }
        }
    }

    /** Builds a program that prints a string, using a multiply loop for each character. */
    private fun printer(text: String): String = buildString {
        for (c in text) {
            val code = c.code
            append("+".repeat(code / 16))
            append("[>++++++++++++++++<-]>")
            append("+".repeat(code % 16))
            append(".[-]<")
        }
    }

    @Test
    fun printsAString() {
        val program = printer("Hello, World!\n")
        for (backend in Backend.entries) {
            assertEquals("Hello, World!\n", execute(backend, program).output, "$backend")
        }
    }

    @Test
    fun catAndReverseUseInputCorrectly() {
        // Both of these rely on EOF storing zero, or they would never see the end of the input.
        assertSameEverywhere(",[.,]", "some input", eof = EofBehavior.ZERO)
        assertEquals("some input", execute(Backend.JIT, ",[.,]", "some input", eof = EofBehavior.ZERO).output)
        assertEquals(
            "tupni",
            execute(Backend.JIT, ">,[>,]<[.<]", "input", eof = EofBehavior.ZERO).output,
        )
    }

    @Test
    fun eofBehaviorsAreDistinct() {
        val readAtEof = "+++,."
        for (backend in listOf(Backend.REFERENCE, Backend.INTERPRETER, Backend.JIT)) {
            assertEquals("\u0003", execute(backend, readAtEof, eof = EofBehavior.UNCHANGED).output)
            assertEquals("\u0000", execute(backend, readAtEof, eof = EofBehavior.ZERO).output)
            assertEquals("ÿ", execute(backend, readAtEof, eof = EofBehavior.MINUS_ONE).output)
        }
    }

    @Test
    fun cellsWrapAtEightBits() {
        assertSameEverywhere("-.")
        assertSameEverywhere("-+.")
        assertSameEverywhere("+".repeat(300) + ".")
        assertEquals("ÿ", execute(Backend.JIT, "-.").output)
        assertEquals(",", execute(Backend.JIT, "+".repeat(300) + ".").output)
    }

    @Test
    fun parserIgnoresCommentsAndRejectsUnbalancedLoops() {
        assertEquals(
            listOf(Node.Add(0, 1), Node.Move(1)),
            Parser.parse("hello + world > !"),
        )
        assertEquals(0, assertFailsWith<BfSyntaxError> { Parser.parse("[") }.position)
        assertEquals(1, assertFailsWith<BfSyntaxError> { Parser.parse("+]") }.position)
        assertEquals(1, assertFailsWith<BfSyntaxError> { Parser.parse("[[[]") }.position)
    }

    @Test
    fun hostileNestingIsRejectedBeforeItCanOverflowTheStack() {
        val error = assertFailsWith<BfSyntaxError> { Parser.parse("[".repeat(100_000)) }
        assertTrue("nested" in error.message!!)
    }

    @Test
    fun theDeepestAcceptedNestingCompilesAndRuns() {
        val depth = Parser.MAX_NESTING
        // The first cell is one, so every level is entered once and leaves by clearing it.
        val program = "+" + "[".repeat(depth) + "-" + "]".repeat(depth) + "."
        for (backend in listOf(Backend.INTERPRETER, Backend.JIT)) {
            assertEquals("\u0000", execute(backend, program).output, "$backend")
        }
    }

    private fun optimized(source: String) = Optimizer.optimize(Parser.parse(source))

    @Test
    fun peepholeFoldsRunsAndOffsets() {
        assertEquals(listOf<Node>(Node.Add(0, 5)), optimized("+++++"))
        assertEquals(listOf<Node>(Node.Move(3)), optimized(">>>"))
        assertEquals(listOf<Node>(Node.Add(2, 1), Node.Move(2)), optimized(">>+"))
        assertEquals(listOf<Node>(Node.Add(2, 1)), optimized(">>+<<"))
        assertEquals(listOf<Node>(Node.Output(1), Node.Move(1)), optimized(">."))
        assertEquals(emptyList<Node>(), optimized("><"))
    }

    @Test
    fun peepholeRecognizesClearAndScanLoops() {
        assertEquals(listOf<Node>(Node.Set(0, 0)), optimized("[-]"))
        assertEquals(listOf<Node>(Node.Set(0, 0)), optimized("[+]"))
        assertEquals(listOf<Node>(Node.Set(0, 0)), optimized("[---]"))
        assertEquals(listOf<Node>(Node.Set(0, 3)), optimized("[-]+++"))
        assertEquals(listOf<Node>(Node.Scan(1)), optimized("[>]"))
        assertEquals(listOf<Node>(Node.Scan(-2)), optimized("[<<]"))
        // An even step can loop forever on an odd value, so it must remain a real loop.
        assertTrue(optimized("[--]").single() is Node.Loop)
    }

    @Test
    fun peepholeRecognizesMultiplyLoops() {
        assertEquals(
            listOf<Node>(Node.MulLoop(listOf(Node.MulLoop.Target(1, 1)))),
            optimized("[->+<]"),
        )
        assertEquals(
            listOf<Node>(
                Node.MulLoop(listOf(Node.MulLoop.Target(1, 2), Node.MulLoop.Target(3, 3))),
            ),
            optimized("[>++>>+++<<<-]"),
        )
        // Not multiply loops: unbalanced movement, a step other than -1, or I/O inside.
        assertTrue(optimized("[->+]").single() is Node.Loop)
        assertTrue(optimized("[+>+<]").single() is Node.Loop)
        assertTrue(optimized("[--->+<]").single() is Node.Loop)
        assertTrue(optimized("[->.<]").single() is Node.Loop)
    }

    @Test
    fun aMultiplyLoopHasTheSameEffectAsRunningIt() {
        for (start in listOf(0, 1, 2, 127, 128, 200, 255)) {
            val prefix = "+".repeat(start)
            assertSameEverywhere("$prefix[->+++>-<<].>.>.")
            assertSameEverywhere("$prefix[>>+<<-]>>.")
        }
    }

    @Test
    fun aMultiplyLoopThatNeverRunsDoesNotTouchItsTargets() {
        // At the left edge, the target would be cell -1. With a zero base this must not fail.
        assertSameEverywhere("[<+>-]+.")
        assertEquals(null, execute(Backend.JIT, "[<+>-]+.").error)
        // With a non-zero base it really does touch cell -1, so it must fail.
        assertSameEverywhere("+[<+>-]+.")
        assertEquals(TapeOutOfBounds::class.java, execute(Backend.JIT, "+[<+>-]+.").error)
    }

    @Test
    fun movingOffTheTapeOnlyFailsWhenACellIsTouched() {
        // Pointer movement alone is harmless, on every backend.
        assertSameEverywhere("<><>+.")
        assertSameEverywhere("<<<<>>>>+.")
        assertEquals(null, execute(Backend.JIT, "<<<<>>>>+.").error)
        // An access while off the tape fails, even if the program would have moved back later.
        assertSameEverywhere("<+>.")
        assertEquals(TapeOutOfBounds::class.java, execute(Backend.JIT, "<+>.").error)
    }

    @Test
    fun cancellingOperationsOffTheTapeStillFail() {
        // `<+-` nets out to nothing, but it does touch cell -1. Dropping it would be a change in
        // behavior, because the unoptimized program stops here and this one would carry on.
        assertSameEverywhere("<+->+.")
        assertEquals(TapeOutOfBounds::class.java, execute(Backend.JIT, "<+->+.").error)
    }

    @Test
    fun outputBeforeAFailureIsNotLost() {
        val program = "+++.<."
        for (backend in Backend.entries) {
            val run = execute(backend, program)
            assertEquals("\u0003", run.output, "$backend")
            assertEquals(TapeOutOfBounds::class.java, run.error, "$backend")
        }
    }

    @Test
    fun fuelStopsInfiniteLoopsAndKeepsPriorOutput() {
        val spin = "+.[]"
        assertEquals(OutOfFuel::class.java, execute(Backend.JIT, spin, fuel = 10_000).error)
        assertEquals("\u0001", execute(Backend.JIT, spin, fuel = 10_000).output)
        assertEquals(OutOfFuel::class.java, execute(Backend.INTERPRETER, spin, fuel = 10_000).error)

        // Every shape of loop has to charge fuel, including ones the optimizer rewrites.
        for (program in listOf("+[>+<]", "+[--]", "+[[]]", "+[>[-]<]")) {
            for (backend in listOf(Backend.JIT, Backend.INTERPRETER)) {
                val run = execute(backend, program, fuel = 1000)
                assertEquals(OutOfFuel::class.java, run.error, "$backend on $program")
            }
        }
    }

    @Test
    fun scanLoopsChargeFuel() {
        val scan = listOf<Node>(Node.Scan(1))
        val programs = listOf(FlatInterpreter(scan), BfJit.compile(scan, metered = true))
        for (program in programs) {
            val tape = ByteArray(1000) { 1 }
            val io = BfIo(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), fuel = 10)
            assertFailsWith<OutOfFuel> { program.run(tape, io) }
        }
    }

    /**
     * A program whose translation would far exceed the 64 KiB method limit. It only loads at
     * all because the compiler splits it up.
     */
    @Test
    fun programsBeyondTheJvmMethodLimitCompileAndRunCorrectly() {
        // A long straight-line run, with distinct offsets so that nothing folds away.
        val straight = buildString {
            repeat(50_000) { append("+>") }
            append("<".repeat(50_000))
            append(".>.")
        }
        assertEquals("\u0001\u0001", execute(Backend.JIT, straight, tapeSize = 60_000).output)

        // A loop with an enormous body.
        val bigLoop = "+[->" + "+>".repeat(30_000) + "<".repeat(30_001) + "]>."
        assertSameEverywhere(bigLoop, tapeSize = 40_000)

        // Very many small loops in sequence. The output stops the optimizer folding them away.
        val manyLoops = "+[-.]".repeat(30_000) + "+."
        assertSameEverywhere(manyLoops)

        // Several levels of big loops, one inside another.
        val inner = "+[->" + "+>".repeat(5_000) + "<".repeat(5_001) + "]"
        val nested = "+[-" + inner.repeat(3) + "+[-" + inner + "]]"
        assertSameEverywhere(nested, tapeSize = 8_000)
    }

    @Test
    fun largeProgramsSplitIntoManyMethodsButNotTooMany() {
        val ir = optimized("+>".repeat(50_000))
        val methods = BfJit.methodCount(ir)
        assertTrue(methods > 10, "only $methods methods for a program this size")
        // 700 KB of bytecode at no less than half a budget per method, plus the tree above them.
        assertTrue(methods < 500, "$methods methods is wasteful, and each one uses constant pool")
    }

    @Test
    fun aProgramTooLargeForOneClassFailsClearlyAndStillRunsInterpreted() {
        // Each cell gets its own add, so that nothing folds away.
        val ir = optimized("+>".repeat(6_000_000))
        assertFailsWith<BfTooLarge> { BfJit.compile(ir) }
        val tape = ByteArray(6_000_001)
        val io = BfIo(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        assertEquals(6_000_000, FlatInterpreter(ir).run(tape, io))
        assertEquals(1, tape[5_999_999])
    }

    private fun randomProgram(random: Random, length: Int): String = buildString {
        var depth = 0
        val alphabet = "+++---<>>.,"
        repeat(length) {
            when (random.nextInt(12)) {
                0 -> if (depth < 5) {
                    append('[')
                    depth++
                }
                1 -> if (depth > 0) {
                    append(']')
                    depth--
                }
                2 -> append(listOf("[-]", "[->+<]", "[>]", "[<]", "[->>++<<]", "[+]").random(random.asKotlin()))
                else -> append(alphabet[random.nextInt(alphabet.length)])
            }
        }
        append("]".repeat(depth))
    }

    private fun Random.asKotlin(): kotlin.random.Random = kotlin.random.Random(nextLong())

    /**
     * The reference interpreter defines correct behavior. Random programs must act the same on
     * the optimized interpreter and on the JIT, with and without optimization. Fuel only bounds
     * the reference run, and programs it cannot finish are skipped.
     */
    @Test
    fun allBackendsAgreeOnRandomPrograms() {
        val random = Random(1234)
        var compared = 0
        var failed = 0
        val limit = 200_000L
        repeat(3000) { round ->
            val source = randomProgram(random, 5 + random.nextInt(120))
            val input = (0 until random.nextInt(8)).map { (random.nextInt(255) + 1).toChar() }.joinToString("")
            val reference = execute(Backend.REFERENCE, source, input, 256, fuel = limit)
            if (reference.error == OutOfFuel::class.java) return@repeat
            compared++
            if (reference.error != null) failed++
            for (backend in listOf(Backend.UNOPTIMIZED_JIT, Backend.INTERPRETER, Backend.JIT)) {
                val got = execute(backend, source, input, 256, fuel = 100_000_000)
                assertEquals(reference.output, got.output, "$backend round $round output for $source")
                assertEquals(reference.error, got.error, "$backend round $round error for $source")
                assertEquals(reference.pointer, got.pointer, "$backend round $round pointer for $source")
                if (reference.tape != null) {
                    assertContentEquals(reference.tape, got.tape, "$backend round $round tape for $source")
                }
            }
        }
        // A generator that stopped producing either kind of run would prove very little.
        assertTrue(compared > 1500, "only compared $compared programs")
        assertTrue(failed > 100, "only $failed programs exercised tape errors")
        assertTrue(compared - failed > 100, "only ${compared - failed} programs ran to completion")
    }

    @Test
    fun compiledProgramsCanRunRepeatedlyAndConcurrently() {
        val program = BfJit.compile(optimized(printer("ok")))
        val threads = (0 until 8).map {
            Thread {
                repeat(200) {
                    val out = ByteArrayOutputStream()
                    program.run(ByteArray(64), BfIo(ByteArrayInputStream(ByteArray(0)), out))
                    assertEquals("ok", out.toString())
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }

    @Test
    fun hiddenClassesDoNotAccumulate() {
        // Each compile defines a new class. This would exhaust metaspace if they never unloaded.
        repeat(5000) {
            val out = ByteArrayOutputStream()
            BfJit.compile(optimized("+.")).run(ByteArray(8), BfIo(ByteArrayInputStream(ByteArray(0)), out))
        }
    }
}
