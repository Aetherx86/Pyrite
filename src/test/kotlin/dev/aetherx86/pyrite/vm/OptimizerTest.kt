package dev.aetherx86.pyrite.vm

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OptimizerTest {
    private fun verified(source: String) = Verifier.verify(Assembler.assemble(source))

    private fun optimized(source: String) = VmOptimizer.optimize(verified(source))

    /** The optimizer must turn [source] into exactly the code [expected] assembles to. */
    private fun assertOptimizesTo(expected: String, source: String) {
        val got = optimized(source).program
        assertContentEquals(
            Assembler.assemble(expected),
            got.code,
            "got:\n${Disassembler.disassemble(got)}",
        )
    }

    private fun outcome(program: VerifiedProgram, jit: Boolean): Any = try {
        if (jit) VmJit.compile(program).run() else StackVm(program).run()
    } catch (e: VmTrap) {
        e.kind
    }

    @Test
    fun constantsFold() {
        assertOptimizesTo("push 42\nhalt", "push 6\npush 7\nmul\nhalt")
        assertOptimizesTo("push 10\nhalt", "push 1\npush 2\nadd\npush 3\nadd\npush 4\nadd\nhalt")
        assertOptimizesTo("push -5\nhalt", "push 5\nneg\nhalt")
        assertOptimizesTo("push 1\nhalt", "push 3\npush 4\nlt\nhalt")
        assertOptimizesTo("push ${Int.MIN_VALUE}\nhalt", "push ${Int.MAX_VALUE}\npush 1\nadd\nhalt")
        assertOptimizesTo("push ${Int.MIN_VALUE}\nhalt", "push ${Int.MIN_VALUE}\npush -1\ndiv\nhalt")
        assertOptimizesTo("push 2\nhalt", "push 1\npush 33\nshl\nhalt")
        // A large folded constant needs the long encoding, and a small one the short.
        assertOptimizesTo("push 65536\nhalt", "push 256\npush 256\nmul\nhalt")
        assertOptimizesTo("push 1\nhalt", "push 65536\npush 65535\nsub\nhalt")
    }

    @Test
    fun divisionByAConstantZeroStillTraps() {
        for (op in listOf("div", "rem")) {
            val result = optimized("push 1\npush 0\n$op\nhalt")
            assertEquals(4, result.program.instructionCount, "nothing may fold")
            for (jit in listOf(false, true)) {
                assertEquals(VmTrap.Kind.DIVIDE_BY_ZERO, outcome(result.program, jit))
            }
        }
    }

    @Test
    fun identitiesAndCancellingPairsDisappear() {
        for (pair in listOf(
            "push 0\nadd", "push 0\nsub", "push 1\nmul", "push 1\ndiv", "push -1\nand", "push 0\nor",
            "push 0\nxor", "push 0\nshl", "push 64\nshr", "dup\ndrop", "neg\nneg", "push 9\ndrop",
            "load 3\ndrop",
        )) {
            assertOptimizesTo("load 0\nhalt", "load 0\n$pair\nhalt")
        }
        assertOptimizesTo("load 0\nload 1\nhalt", "load 0\nload 1\nswap\nswap\nhalt")
        // Not identities.
        assertOptimizesTo("load 0\npush 1\nshl\nhalt", "load 0\npush 1\nshl\nhalt")
        assertOptimizesTo("load 0\npush 0\nmul\nhalt", "load 0\npush 0\nmul\nhalt")
    }

    @Test
    fun branchesOnConstantsFoldAndDeadCodeGoes() {
        val source = """
            push 0
            jz yes
            push 1
            halt
        yes:
            push 2
            halt
        """
        assertOptimizesTo("push 2\nhalt", source)
        assertOptimizesTo("push 1\nhalt", source.replace("push 0", "push 5"))
        assertOptimizesTo("push 1\nhalt", source.replace("jz", "jnz"))
        // A function nobody calls is dropped.
        assertOptimizesTo("push 1\nhalt", "push 1\nhalt\nf:\nenter 0\npush 1\nret")
    }

    @Test
    fun jumpsAreThreaded() {
        val source = """
            load 0
            jz a
            push 1
            halt
        a:
            jmp b
        b:
            jmp c
        c:
            push 2
            halt
        """
        assertOptimizesTo("load 0\njz c\npush 1\nhalt\nc:\npush 2\nhalt", source)
        // A conditional jump to the next instruction only pops its operand.
        assertOptimizesTo(
            "load 0\nload 1\nadd\ndrop\nload 0\nhalt",
            "load 0\nload 1\nadd\njz next\nnext:\nload 0\nhalt",
        )
        // An infinite loop survives as one.
        val spin = optimized("top:\njmp top")
        assertEquals(VmTrap.Kind.OUT_OF_FUEL, assertFailsWith<VmTrap> { StackVm(spin.program).run(100) }.kind)
    }

    @Test
    fun aLabelOnARemovedInstructionMovesToItsSuccessor() {
        // `top` labels the `push 0; add` pair that disappears, so the back edge must land on the
        // `load 0` after it, and with the same stack depth.
        val source = """
            push 5
            store 0
            push 0
        top:
            push 0
            add
            load 0
            add
            load 0
            push 1
            sub
            dup
            store 0
            jnz top
            halt
        """
        val result = optimized(source)
        assertEquals(result.report.bytesBefore - 4, result.report.bytesAfter)
        for (jit in listOf(false, true)) assertEquals(15, outcome(result.program, jit))
    }

    @Test
    fun functionsAreOptimizedToo() {
        val source = """
            push 4
            call f
            halt
        f:
            enter 1
            load 0
            push 2
            push 3
            mul
            add
            push 0
            add
            ret
        """
        assertOptimizesTo("push 4\ncall f\nhalt\nf:\nenter 1\nload 0\npush 6\nadd\nret", source)
    }

    @Test
    fun theReportCountsWhatChanged() {
        val report = optimized("push 6\npush 7\nmul\nhalt\nadd\nhalt").report
        assertEquals(OptimizationReport(10, 4, 6, 2, 2), report)
        // Code with nothing to do takes one round and comes back unchanged.
        val plain = verified("load 0\nload 1\nadd\nhalt")
        val again = VmOptimizer.optimize(plain)
        assertEquals(1, again.report.rounds)
        assertContentEquals(plain.code, again.program.code)
    }

    /**
     * The optimized program must produce the same result or trap as the original, on both
     * backends, must never be larger, and optimizing it again must change nothing.
     */
    @Test
    fun optimizedProgramsBehaveIdenticallyOnRandomInputs() {
        val random = Random(4242)
        var shrank = 0
        var completed = 0
        var trapped = 0
        repeat(1500) { round ->
            val source = RandomPrograms.generate(random)
            val original = verified(source)
            val result = VmOptimizer.optimize(original)
            val optimized = result.program
            val expected = outcome(original, jit = false)
            if (expected is Int) completed++ else trapped++
            assertEquals(expected, outcome(original, jit = true), "round $round original JIT:\n$source")
            assertEquals(expected, outcome(optimized, jit = false), "round $round optimized:\n$source")
            assertEquals(expected, outcome(optimized, jit = true), "round $round optimized JIT:\n$source")
            assertTrue(optimized.code.size <= original.code.size, "round $round grew")
            if (optimized.code.size < original.code.size) shrank++
            assertContentEquals(
                optimized.code,
                VmOptimizer.optimize(optimized).program.code,
                "round $round is not a fixed point:\n$source",
            )
        }
        assertTrue(shrank > 1200, "only $shrank programs shrank")
        assertTrue(completed > 200, "only $completed programs completed")
        assertTrue(trapped > 100, "only $trapped programs trapped")
    }

    @Test
    fun optimizedCodeExecutesFewerInstructions() {
        val source = """
            push 1000
            store 0
        top:
            load 0
            jz done
            load 1
            push 2
            push 3
            mul
            add
            push 0
            add
            store 1
            load 0
            push 1
            sub
            store 0
            push 1
            jnz top
        done:
            load 1
            halt
        """
        val before = StackVm(verified(source)).also { assertEquals(6000, it.run()) }.executed
        val after = StackVm(optimized(source).program).also { assertEquals(6000, it.run()) }.executed
        assertTrue(after * 10 < before * 7, "$before instructions before, $after after")
    }
}
