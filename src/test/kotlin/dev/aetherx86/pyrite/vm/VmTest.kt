package dev.aetherx86.pyrite.vm

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VmTest {
    private fun verified(source: String) = Verifier.verify(Assembler.assemble(source))

    /** Runs on both backends and requires them to agree. */
    private fun both(source: String): Int {
        val program = verified(source)
        val interpreted = StackVm(program).run()
        val compiled = VmJit.compile(program).run()
        assertEquals(interpreted, compiled, "backends disagree on:\n$source")
        return interpreted
    }

    @Test
    fun arithmeticAndStackOperationsWork() {
        assertEquals(7, both("push 3\npush 4\nadd\nhalt"))
        assertEquals(-1, both("push 3\npush 4\nsub\nhalt"))
        assertEquals(12, both("push 3\npush 4\nmul\nhalt"))
        assertEquals(3, both("push 10\npush 3\ndiv\nhalt"))
        assertEquals(1, both("push 10\npush 3\nrem\nhalt"))
        assertEquals(-5, both("push 5\nneg\nhalt"))
        assertEquals(8, both("push 12\npush 10\nand\nhalt"))
        assertEquals(14, both("push 12\npush 10\nor\nhalt"))
        assertEquals(6, both("push 12\npush 10\nxor\nhalt"))
        assertEquals(40, both("push 5\npush 3\nshl\nhalt"))
        assertEquals(-2, both("push -8\npush 2\nshr\nhalt"))
        assertEquals(1, both("push 2\npush 5\nlt\nhalt"))
        assertEquals(0, both("push 5\npush 2\nlt\nhalt"))
        assertEquals(1, both("push 5\npush 5\neq\nhalt"))
        assertEquals(6, both("push 3\ndup\nadd\nhalt"))
        assertEquals(2, both("push 2\npush 9\ndrop\nhalt"))
        assertEquals(-1, both("push 3\npush 4\nswap\nsub\nneg\nneg\nneg\nhalt"))
    }

    @Test
    fun constantsCoverTheWholeIntRange() {
        for (value in listOf(0, 1, -1, 127, 128, -128, -129, 32767, 32768, -32768, -32769, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(value, both("push $value\nhalt"), "value $value")
        }
    }

    @Test
    fun arithmeticWrapsLikeInts() {
        assertEquals(Int.MIN_VALUE, both("push ${Int.MAX_VALUE}\npush 1\nadd\nhalt"))
        assertEquals(Int.MIN_VALUE, both("push ${Int.MIN_VALUE}\npush -1\ndiv\nhalt"))
        assertEquals(0, both("push ${Int.MIN_VALUE}\npush -1\nrem\nhalt"))
        assertEquals(1, both("push 1\npush 32\nshl\nhalt"), "shift counts are masked")
    }

    @Test
    fun loopsAndLocalsCompute() {
        // Sums 1..100.
        val sum = """
            push 100
            store 0
            push 0
            store 1
        top:
            load 0
            jz done
            load 1
            load 0
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
        """
        assertEquals(5050, both(sum))
    }

    @Test
    fun conditionalJumpsTakeTheCorrectBranch() {
        assertEquals(7, both("push 0\njnz skip\npush 7\nhalt\nskip:\npush 9\nhalt"))
        assertEquals(9, both("push 1\njnz skip\npush 7\nhalt\nskip:\npush 9\nhalt"))
        assertEquals(9, both("push 0\njz skip\npush 7\nhalt\nskip:\npush 9\nhalt"))
        assertEquals(7, both("push 1\njz skip\npush 7\nhalt\nskip:\npush 9\nhalt"))
        // A negative value counts as true.
        assertEquals(9, both("push -1\njnz skip\npush 7\nhalt\nskip:\npush 9\nhalt"))
        // A do-while countdown, which only terminates if JNZ falls through on zero.
        val countdown = """
            push 5
            store 0
        top:
            load 1
            push 2
            add
            store 1
            load 0
            push 1
            sub
            dup
            store 0
            jnz top
            load 1
            halt
        """
        assertEquals(10, both(countdown))
    }

    @Test
    fun localsStartAtZeroOnEveryRun() {
        val program = verified("load 7\npush 5\nstore 7\nhalt")
        val vm = StackVm(program)
        assertEquals(0, vm.run())
        assertEquals(0, vm.run(), "the previous run's store must not leak into this one")
    }

    @Test
    fun divisionByZeroTrapsOnBothBackends() {
        val div = verified("push 1\npush 0\ndiv\nhalt")
        val rem = verified("push 1\npush 0\nrem\nhalt")
        for (program in listOf(div, rem)) {
            assertEquals(
                VmTrap.Kind.DIVIDE_BY_ZERO,
                assertFailsWith<VmTrap> { StackVm(program).run() }.kind,
            )
            assertEquals(
                VmTrap.Kind.DIVIDE_BY_ZERO,
                assertFailsWith<VmTrap> { VmJit.compile(program).run() }.kind,
            )
        }
    }

    @Test
    fun fuelStopsAnInfiniteLoop() {
        val vm = StackVm(verified("top:\njmp top"))
        assertEquals(VmTrap.Kind.OUT_OF_FUEL, assertFailsWith<VmTrap> { vm.run(fuel = 1000) }.kind)
        assertEquals(1000L, vm.executed)
    }

    @Test
    fun verifierRejectsUnsafePrograms() {
        val cases = listOf(
            byteArrayOf() to "empty",
            byteArrayOf(0x7F) to "unknown opcode",
            byteArrayOf(Op.CONST16, 0) to "truncated",
            byteArrayOf(Op.CONST16, 0, 0) to "run off the end",
            byteArrayOf(Op.ADD, Op.HALT) to "underflow",
            byteArrayOf(Op.DROP, Op.HALT) to "underflow",
            byteArrayOf(Op.HALT) to "underflow",
            byteArrayOf(Op.JMP, 0, 9, Op.HALT) to "invalid jump target",
            // The target lands inside the operand of the CONST16 at offset 3.
            byteArrayOf(Op.JMP, 0, 4, Op.CONST16, 0, 0, Op.HALT) to "invalid jump target",
        )
        for ((code, needle) in cases) {
            val error = assertFailsWith<VerifyError>(needle) { Verifier.verify(code) }
            assertTrue(needle in error.message!!, "expected '$needle' in '${error.message}'")
        }
    }

    @Test
    fun verifierRejectsInconsistentStackDepths() {
        // The branch that is taken reaches `end` with a different depth.
        val source = """
            push 1
            jz end
            push 2
        end:
            push 3
            halt
        """
        val error = assertFailsWith<VerifyError> { verified(source) }
        assertTrue("inconsistent" in error.message!!)
    }

    @Test
    fun verifierRejectsAStackThatGrowsInALoop() {
        val source = """
        top:
            push 1
            jmp top
        """
        val error = assertFailsWith<VerifyError> { verified(source) }
        assertTrue("inconsistent" in error.message!!)
    }

    @Test
    fun verifierEnforcesTheStackLimit() {
        val deep = buildString {
            repeat(Verifier.MAX_STACK + 1) { append("push 1\n") }
            append("halt\n")
        }
        val error = assertFailsWith<VerifyError> { verified(deep) }
        assertTrue("overflow" in error.message!!)

        val atLimit = buildString {
            repeat(Verifier.MAX_STACK) { append("push 1\n") }
            append("halt\n")
        }
        assertEquals(1, both(atLimit))
    }

    @Test
    fun unreachableCodeIsToleratedByBothBackends() {
        // Dead code is decoded and checked for shape, but never needs a consistent stack.
        assertEquals(5, both("push 5\nhalt\nadd\nadd\nhalt"))
    }

    @Test
    fun assemblerReportsErrorsWithLineNumbers() {
        val cases = listOf(
            "bogus" to "unknown instruction",
            "push" to "needs an operand",
            "push x" to "invalid integer",
            "push 99999999999" to "invalid integer",
            "add 1" to "takes no operand",
            "push 1 2" to "too many operands",
            "load 256" to "out of range",
            "store -1" to "out of range",
            "jmp nowhere" to "undefined label",
            "a:\na:" to "duplicate label",
            ":" to "bad or duplicate",
        )
        for ((source, needle) in cases) {
            val error = assertFailsWith<AssemblyError>(source) { Assembler.assemble(source) }
            assertTrue(needle in error.message!!, "expected '$needle' in '${error.message}'")
        }
        assertEquals(3, assertFailsWith<AssemblyError> { Assembler.assemble("; note\n\nbogus") }.line)
    }

    @Test
    fun aProgramTooLargeForOneJvmMethodFailsClearly() {
        // A two-byte `dup; lt` becomes nine bytes of JVM code, since a comparison has to branch.
        val big = "push 1\n" + "dup\nlt\n".repeat(30_000) + "halt\n"
        val program = verified(big)
        assertEquals(0, StackVm(program).run(), "the interpreter has no such limit")
        assertFailsWith<JitUnavailable> { VmJit.compile(program) }
    }

    /** Builds a valid random program, using the verifier's own rules as the specification. */
    private fun randomProgram(random: Random): String = buildString {
        val binary = listOf("add", "sub", "mul", "and", "or", "xor", "shl", "shr", "lt", "eq", "div", "rem")
        // Every local starts at zero, which is the loop counter below.
        appendLine("push ${random.nextInt(40)}")
        appendLine("store 0")
        appendLine("top:")
        appendLine("load 0")
        appendLine("jz done")
        var depth = 0
        repeat(5 + random.nextInt(40)) {
            when {
                depth < 2 || random.nextInt(3) == 0 -> {
                    if (random.nextBoolean()) {
                        appendLine("push ${random.nextInt()}")
                    } else {
                        appendLine("load ${1 + random.nextInt(4)}")
                    }
                    depth++
                }
                random.nextInt(6) == 0 -> appendLine("dup").also { depth++ }
                random.nextInt(6) == 0 -> appendLine("swap")
                random.nextInt(6) == 0 -> appendLine("neg")
                random.nextInt(6) == 0 -> {
                    appendLine("store ${1 + random.nextInt(4)}")
                    depth--
                }
                else -> {
                    appendLine(binary[random.nextInt(binary.size)])
                    depth--
                }
            }
        }
        repeat(depth) {
            appendLine("store ${1 + random.nextInt(4)}")
        }
        appendLine("load 0")
        appendLine("push 1")
        appendLine("sub")
        appendLine("store 0")
        appendLine("jmp top")
        appendLine("done:")
        appendLine("load 1")
        appendLine("load 2")
        appendLine("xor")
        appendLine("load 3")
        appendLine("xor")
        appendLine("load 4")
        appendLine("xor")
        appendLine("halt")
    }

    @Test
    fun bothBackendsAgreeOnRandomPrograms() {
        val random = Random(77)
        var completed = 0
        var trapped = 0
        repeat(1500) { round ->
            val source = randomProgram(random)
            val program = verified(source)
            val interpreted = runCatching { StackVm(program).run() }
            val compiled = runCatching { VmJit.compile(program).run() }
            if (interpreted.isSuccess) {
                completed++
                assertEquals(interpreted.getOrNull(), compiled.getOrNull(), "round $round:\n$source")
            } else {
                trapped++
                assertEquals(
                    (interpreted.exceptionOrNull() as VmTrap).kind,
                    (compiled.exceptionOrNull() as? VmTrap)?.kind,
                    "round $round:\n$source",
                )
            }
        }
        assertTrue(completed > 200, "only $completed programs completed")
        assertTrue(trapped > 200, "only $trapped programs trapped")
    }

    @Test
    fun theDispatchLoopDoesNotAllocate() {
        // Counts bytes allocated by this thread across a long run of the hot loop.
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
            as com.sun.management.ThreadMXBean
        val vm = StackVm(
            verified(
                """
                push 30000
                store 0
            top:
                load 0
                jz done
                load 0
                push 1
                sub
                store 0
                jmp top
            done:
                push 0
                halt
                """,
            ),
        )
        vm.run()
        val id = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(id)
        vm.run()
        val allocated = bean.getThreadAllocatedBytes(id) - before
        assertTrue(allocated < 4096, "the interpreter allocated $allocated bytes")
    }
}
