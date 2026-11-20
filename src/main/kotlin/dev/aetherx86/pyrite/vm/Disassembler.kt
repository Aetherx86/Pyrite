package dev.aetherx86.pyrite.vm

/**
 * Turns verified bytecode back into assembler text.
 *
 * Every jump and call target gets a label named after its offset, such as `L0012`, and each
 * instruction carries a comment with its offset and the stack depth the verifier proved for it.
 * Output for assembled code with nothing unreachable reassembles to the same bytes. Unreachable
 * instructions are listed as comments only, because nothing checked their jump targets.
 */
object Disassembler {
    fun disassemble(program: VerifiedProgram): String {
        val code = program.code
        val targets = HashSet<Int>()
        var pc = 0
        while (pc < code.size) {
            val op = code[pc]
            if (Op.isBranch(op) && program.isReachable(pc)) targets.add(Verifier.readU16(code, pc + 1))
            pc += Op.length(op)
        }
        val entries = program.functions.associateBy { it.entry }

        return buildString {
            pc = 0
            while (pc < code.size) {
                val op = code[pc]
                val text = instructionText(code, pc)
                val function = entries[pc]
                if (function != null) {
                    if (isNotEmpty()) appendLine()
                    appendLine(
                        "; function: ${function.argc} argument(s), ${function.localCount} local(s), " +
                            "max stack ${function.maxStack}",
                    )
                }
                if (pc in targets) appendLine("${label(pc)}:")
                if (program.isReachable(pc)) {
                    val depth = program.stackDepthAt(pc)
                    appendLine("    ${text.padEnd(16)} ; %04x  depth %d".format(pc, depth))
                } else {
                    appendLine("    ; %04x  unreachable: %s".format(pc, text))
                }
                pc += Op.length(op)
            }
        }
    }

    fun label(offset: Int): String = "L%04x".format(offset)

    private fun instructionText(code: ByteArray, pc: Int): String {
        val op = code[pc]
        val name = Op.mnemonic(op) ?: error("verified code held opcode $op")
        return when {
            op == Op.CONST16 || op == Op.CONST32 -> "$name ${Verifier.readConst(code, pc)}"
            Op.isBranch(op) -> "$name ${label(Verifier.readU16(code, pc + 1))}"
            op == Op.LOAD || op == Op.STORE || op == Op.ENTER -> "$name ${code[pc + 1].toInt() and 0xFF}"
            else -> name
        }
    }
}
