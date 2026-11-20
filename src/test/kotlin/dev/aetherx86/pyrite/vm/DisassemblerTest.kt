package dev.aetherx86.pyrite.vm

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DisassemblerTest {
    private fun verified(source: String) = Verifier.verify(Assembler.assemble(source))

    @Test
    fun outputIsAnnotatedWithOffsetsDepthsAndFunctions() {
        val text = Disassembler.disassemble(
            verified("push 3\ncall sq\nhalt\nsq:\nenter 1\nload 0\nload 0\nmul\nret"),
        )
        val expected = """
                push 3           ; 0000  depth 0
                call L0007       ; 0003  depth 1
                halt             ; 0006  depth 1

            ; function: 1 argument(s), 1 local(s), max stack 2
            L0007:
                enter 1          ; 0007  depth 0
                load 0           ; 0009  depth 0
                load 0           ; 000b  depth 1
                mul              ; 000d  depth 2
                ret              ; 000e  depth 1
        """.trimIndent()
        // The source file may have been checked out with CRLF line endings.
        assertEquals(expected.replace("\r\n", "\n") + "\n", text)
    }

    @Test
    fun unreachableInstructionsBecomeComments() {
        val text = Disassembler.disassemble(verified("push 5\nhalt\ndead:\nadd\njmp dead"))
        assertTrue("; 0004  unreachable: add" in text, text)
        assertTrue("; 0005  unreachable: jmp L0004" in text, text)
        // The comment hides them from the assembler, which keeps only the live code.
        assertContentEquals(Assembler.assemble("push 5\nhalt"), Assembler.assemble(text))
    }

    @Test
    fun randomProgramsSurviveARoundTrip() {
        val random = Random(99)
        var exact = 0
        repeat(800) { round ->
            val source = RandomPrograms.generate(random, foldable = random.nextBoolean())
            val code = Assembler.assemble(source)
            val program = Verifier.verify(code)
            val text = Disassembler.disassemble(program)
            val again = Verifier.verify(Assembler.assemble(text))
            if (program.instructionCount == program.reachableInstructionCount) {
                exact++
                assertContentEquals(code, again.code, "round $round:\n$text")
            } else {
                // Dead code became comments, so only the live code comes back.
                assertEquals(program.reachableInstructionCount, again.instructionCount, "round $round")
                val expected = runCatching { StackVm(program).run() }
                val got = runCatching { StackVm(again).run() }
                assertEquals(expected.getOrNull(), got.getOrNull(), "round $round:\n$text")
                assertEquals(
                    (expected.exceptionOrNull() as? VmTrap)?.kind,
                    (got.exceptionOrNull() as? VmTrap)?.kind,
                    "round $round:\n$text",
                )
            }
        }
        assertTrue(exact > 300, "only $exact programs had no dead code")
    }
}
