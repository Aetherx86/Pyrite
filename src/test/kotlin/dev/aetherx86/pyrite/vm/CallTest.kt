package dev.aetherx86.pyrite.vm

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CallTest {
    private fun verified(source: String) = Verifier.verify(Assembler.assemble(source))

    /** Runs on both backends, which must agree on the result or the trap. */
    private fun both(source: String): Int {
        val program = verified(source)
        val interpreted = runCatching { StackVm(program).run() }
        val compiled = runCatching { VmJit.compile(program).run() }
        val trap = (interpreted.exceptionOrNull() as? VmTrap)?.kind
        assertEquals(trap, (compiled.exceptionOrNull() as? VmTrap)?.kind, "traps differ on:\n$source")
        if (trap != null) throw interpreted.exceptionOrNull()!!
        assertEquals(interpreted.getOrThrow(), compiled.getOrThrow(), "backends disagree on:\n$source")
        return interpreted.getOrThrow()
    }

    private val fib = """
            push 20
            call fib
            halt
        fib:
            enter 1
            load 0
            push 2
            lt
            jz recurse
            load 0
            ret
        recurse:
            load 0
            push 1
            sub
            call fib
            load 0
            push 2
            sub
            call fib
            add
            ret
    """

    /** Recurses n times, so `call down` with argument n uses n + 1 frames. */
    private fun countdown(n: Int) = """
            push $n
            call down
            halt
        down:
            enter 1
            load 0
            jz base
            load 0
            push 1
            sub
            call down
            push 1
            add
            ret
        base:
            push 0
            ret
    """

    @Test
    fun recursionComputesOnBothBackends() {
        assertEquals(6765, both(fib))
    }

    @Test
    fun argumentsArriveInOrderAndValuesBelowThemSurvive() {
        val source = """
            push 100
            push 10
            push 3
            call minus
            add
            halt
        minus:
            enter 2
            load 0
            load 1
            sub
            ret
        """
        assertEquals(107, both(source))
    }

    @Test
    fun framesHaveTheirOwnLocals() {
        // The callee writes local 0, which is the caller's local 0 too by number but not by
        // storage, and reads local 5, which must start at zero on every call.
        val source = """
            push 11
            store 0
            push 4
            call bump
            call bump
            load 0
            add
            halt
        bump:
            enter 1
            load 5
            push 1000
            add
            store 5
            load 0
            push 1
            add
            store 0
            load 0
            load 5
            add
            ret
        """
        // bump(4) = 5 + 1000 = 1005, bump(1005) = 1006 + 1000 = 2006, plus the caller's 11.
        assertEquals(2017, both(source))
    }

    @Test
    fun functionsWithoutArgumentsAndNestedCallsWork() {
        val source = """
            call seven
            call double
            call seven
            mul
            halt
        double:
            enter 1
            load 0
            call seven
            push 7
            sub
            add
            load 0
            add
            ret
        seven:
            enter 0
            push 7
            ret
        """
        assertEquals(98, both(source))
    }

    @Test
    fun aFunctionBodyCanLieBeforeItsEntry() {
        // The JIT has to start the method at the instruction after ENTER, not at the first
        // instruction it owns in code order.
        val source = """
            push 5
            call f
            halt
        tail:
            load 0
            push 3
            mul
            ret
        f:
            enter 1
            jmp tail
        """
        assertEquals(15, both(source))
    }

    @Test
    fun theCallDepthLimitIsExactAndTheSameOnBothBackends() {
        assertEquals(StackVm.MAX_CALL_DEPTH - 1, both(countdown(StackVm.MAX_CALL_DEPTH - 1)))
        val error = assertFailsWith<VmTrap> { both(countdown(StackVm.MAX_CALL_DEPTH)) }
        assertEquals(VmTrap.Kind.CALL_DEPTH_EXCEEDED, error.kind)
        // Unbounded recursion stops the same way.
        val forever = "push 0\ncall f\nhalt\nf:\nenter 0\ncall f\nret"
        assertEquals(VmTrap.Kind.CALL_DEPTH_EXCEEDED, assertFailsWith<VmTrap> { both(forever) }.kind)
    }

    @Test
    fun anInterpreterIsReusableAfterATrapInsideAFunction() {
        // Local 0 decides whether the run divides by zero deep inside a call. The trap leaves
        // frames behind, and the next run must start from a clean main frame regardless.
        val source = """
            load 0
            push 1
            store 0
            call f
            halt
        f:
            enter 1
            push 1
            load 0
            div
            ret
        """
        val vm = StackVm(verified(source))
        assertEquals(VmTrap.Kind.DIVIDE_BY_ZERO, assertFailsWith<VmTrap> { vm.run() }.kind)
        assertEquals(VmTrap.Kind.DIVIDE_BY_ZERO, assertFailsWith<VmTrap> { vm.run() }.kind)
        val deep = StackVm(verified(countdown(StackVm.MAX_CALL_DEPTH)))
        assertFailsWith<VmTrap> { deep.run() }
        assertFailsWith<VmTrap> { deep.run() }
    }

    @Test
    fun theVerifierDescribesEachFunction() {
        val program = verified(fib)
        assertEquals(listOf(FunctionInfo(entry = 7, argc = 1, localCount = 1, maxStack = 3)), program.functions)
        assertEquals(0, program.ownerOf(0))
        assertEquals(7, program.ownerOf(9))
        assertEquals(0, program.stackDepthAt(9))
        assertEquals(1, program.stackDepthAt(3), "the call in main sees the argument")
        assertEquals(1, program.stackDepthAt(6), "and leaves the result")
    }

    @Test
    fun uncalledFunctionsAreUnreachableAndIgnored() {
        val program = verified("push 1\nhalt\nf:\nenter 0\nadd\nret")
        assertTrue(program.functions.isEmpty())
        assertTrue(!program.isReachable(4))
        assertEquals(1, VmJit.compile(program).run())
    }

    @Test
    fun theVerifierRejectsMisusedFunctions() {
        val cases = listOf(
            "push 1\nret" to "ret outside a function",
            "push 1\ncall f\nhalt\nf:\nenter 1\nload 0\nhalt" to "halt inside the function",
            "push 1\ncall f\nhalt\nf:\nhalt" to "not a function entry",
            "push 1\njmp f\nf:\nenter 0\npush 1\nret" to "without a call",
            "push 1\ndrop\nf:\nenter 0\npush 1\nret" to "without a call",
            "push 1\ncall f\nhalt\nf:\nenter 0\npush 1\npush 2\nret" to "exactly one value",
            "push 1\ncall f\nhalt\nf:\nenter 0\nret" to "exactly one value",
            "push 1\ncall f\nhalt\nf:\nenter 2\npush 1\nret" to "underflow",
            "push 1\ncall f\nback:\nhalt\nf:\nenter 0\npush 1\njmp back" to "into the main program",
        )
        for ((source, needle) in cases) {
            val error = assertFailsWith<VerifyError>(source) { verified(source) }
            assertTrue(needle in error.message!!, "expected '$needle' in '${error.message}' for\n$source")
        }
        // Jumping from one function into the body of another.
        val crossing = """
            call f
            call g
            add
            halt
        f:
            enter 0
        inside:
            push 1
            ret
        g:
            enter 0
            jmp inside
        """
        assertTrue("into the function at" in assertFailsWith<VerifyError> { verified(crossing) }.message!!)
        // The JVM limits a method to 255 parameter slots, one of which carries the call depth.
        val tooMany = byteArrayOf(Op.CALL, 0, 4, Op.HALT, Op.ENTER, 255.toByte(), Op.CONST16, 0, 0, Op.RET)
        assertTrue("at most" in assertFailsWith<VerifyError> { Verifier.verify(tooMany) }.message!!)
        assertTrue("out of range" in assertFailsWith<AssemblyError> { Assembler.assemble("enter 255") }.message!!)
    }

    @Test
    fun aWarmInterpreterAllocatesNothingAcrossCalls() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
            as com.sun.management.ThreadMXBean
        val vm = StackVm(verified(fib))
        vm.run()
        val id = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(id)
        vm.run()
        val allocated = bean.getThreadAllocatedBytes(id) - before
        assertTrue(allocated < 4096, "the interpreter allocated $allocated bytes")
    }

    @Test
    fun compiledFunctionsAreSafeToRunConcurrently() {
        val program = VmJit.compile(verified(fib))
        val threads = (0 until 8).map {
            Thread { repeat(50) { assertEquals(6765, program.run()) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }

    @Test
    fun bothBackendsAgreeOnRandomProgramsWithCalls() {
        val random = Random(2026)
        var completed = 0
        var trapped = 0
        var withCalls = 0
        repeat(1200) { round ->
            val source = RandomPrograms.generate(random, functions = 1 + random.nextInt(4))
            val program = verified(source)
            if (program.functions.isNotEmpty()) withCalls++
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
        assertTrue(trapped > 100, "only $trapped programs trapped")
        assertTrue(withCalls > 600, "only $withCalls programs called a function")
    }
}
