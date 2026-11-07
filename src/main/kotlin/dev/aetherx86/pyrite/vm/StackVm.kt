package dev.aetherx86.pyrite.vm

/**
 * Opcodes, stored as bytes in a flat array. There is one object per program rather than one per
 * instruction, so the dispatch loop allocates nothing and stays cache-friendly.
 */
object Op {
    const val CONST16: Byte = 0x01
    const val CONST32: Byte = 0x02
    const val ADD: Byte = 0x03
    const val SUB: Byte = 0x04
    const val MUL: Byte = 0x05
    const val DIV: Byte = 0x06
    const val REM: Byte = 0x07
    const val AND: Byte = 0x08
    const val OR: Byte = 0x09
    const val XOR: Byte = 0x0A
    const val SHL: Byte = 0x0B
    const val SHR: Byte = 0x0C
    const val NEG: Byte = 0x0D
    const val LT: Byte = 0x0E
    const val EQ: Byte = 0x0F
    const val DUP: Byte = 0x10
    const val DROP: Byte = 0x11
    const val SWAP: Byte = 0x12
    const val LOAD: Byte = 0x13
    const val STORE: Byte = 0x14
    const val JMP: Byte = 0x15
    const val JZ: Byte = 0x16
    const val JNZ: Byte = 0x17
    const val HALT: Byte = 0x18

    /** Encoded length of an instruction, or -1 for an unknown opcode. */
    fun length(op: Byte): Int = when (op) {
        CONST16, JMP, JZ, JNZ -> 3
        CONST32 -> 5
        LOAD, STORE -> 2
        ADD, SUB, MUL, DIV, REM, AND, OR, XOR, SHL, SHR, NEG, LT, EQ, DUP, DROP, SWAP, HALT -> 1
        else -> -1
    }

    /** Stack effect as (values popped, values pushed). */
    fun effect(op: Byte): Pair<Int, Int> = when (op) {
        CONST16, CONST32, LOAD -> 0 to 1
        ADD, SUB, MUL, DIV, REM, AND, OR, XOR, SHL, SHR, LT, EQ -> 2 to 1
        NEG -> 1 to 1
        DUP -> 1 to 2
        DROP, STORE, JZ, JNZ -> 1 to 0
        SWAP -> 2 to 2
        HALT -> 1 to 0
        else -> 0 to 0
    }
}

class VerifyError(val offset: Int, message: String) : Exception("at offset $offset: $message")

class VmTrap(val kind: Kind) : Exception(kind.name) {
    enum class Kind { DIVIDE_BY_ZERO, OUT_OF_FUEL }
}

/** Bytecode that has passed verification. The only way to obtain one is [Verifier.verify]. */
class VerifiedProgram internal constructor(
    val code: ByteArray,
    val maxStack: Int,
    internal val instructionStarts: BooleanArray,
) {
    /** Number of locals the program can touch. */
    val localCount: Int = 256
}

/**
 * Proves a program safe before it runs, in the style of the JVM's own verifier.
 *
 * Verified code cannot underflow or overflow the stack, cannot jump into the middle of an
 * instruction, cannot run off the end of the code, and reaches each instruction with one
 * consistent stack depth. That last property is what allows direct translation to JVM bytecode.
 */
object Verifier {
    const val MAX_STACK = 1024

    fun verify(code: ByteArray): VerifiedProgram {
        if (code.isEmpty()) throw VerifyError(0, "empty program")
        val starts = BooleanArray(code.size)
        var pc = 0
        var lastOp: Byte = 0
        while (pc < code.size) {
            val op = code[pc]
            val length = Op.length(op)
            if (length < 0) throw VerifyError(pc, "unknown opcode 0x%02x".format(op))
            if (pc + length > code.size) throw VerifyError(pc, "truncated instruction")
            starts[pc] = true
            lastOp = op
            pc += length
        }
        if (lastOp != Op.HALT && lastOp != Op.JMP) {
            throw VerifyError(code.size, "execution can run off the end of the program")
        }

        val depthAt = IntArray(code.size) { -1 }
        val work = ArrayDeque<Int>()
        var maxDepth = 0

        fun flow(target: Int, depth: Int, from: Int) {
            if (target < 0 || target >= code.size || !starts[target]) {
                throw VerifyError(from, "invalid jump target $target")
            }
            when (depthAt[target]) {
                -1 -> {
                    depthAt[target] = depth
                    work.addLast(target)
                }
                depth -> {}
                else -> throw VerifyError(
                    target,
                    "inconsistent stack depth: ${depthAt[target]} and $depth",
                )
            }
        }

        flow(0, 0, 0)
        while (work.isNotEmpty()) {
            val at = work.removeFirst()
            val op = code[at]
            val depth = depthAt[at]
            val (pops, pushes) = Op.effect(op)
            if (depth < pops) throw VerifyError(at, "stack underflow")
            val after = depth - pops + pushes
            if (after > MAX_STACK) throw VerifyError(at, "stack overflow")
            maxDepth = maxOf(maxDepth, after, depth)
            val next = at + Op.length(op)
            when (op) {
                Op.HALT -> {}
                Op.JMP -> flow(readU16(code, at + 1), after, at)
                Op.JZ, Op.JNZ -> {
                    flow(readU16(code, at + 1), after, at)
                    flow(next, after, at)
                }
                else -> flow(next, after, at)
            }
        }
        return VerifiedProgram(code, maxOf(maxDepth, 1), starts)
    }

    internal fun readU16(code: ByteArray, at: Int): Int =
        ((code[at].toInt() and 0xFF) shl 8) or (code[at + 1].toInt() and 0xFF)
}

/**
 * Zero-boxing bytecode interpreter. Each instance owns its stack and locals, and is not safe to
 * share between threads.
 */
class StackVm(private val program: VerifiedProgram) {
    private val code = program.code
    private val stack = IntArray(program.maxStack + 2)
    private val locals = IntArray(program.localCount)

    /** Number of instructions executed by the last [run]. */
    var executed = 0L
        private set

    /** Runs until `HALT` and returns the top of the stack. */
    fun run(fuel: Long = Long.MAX_VALUE): Int {
        locals.fill(0)
        var sp = 0
        var ip = 0
        var remaining = fuel
        val c = code
        val s = stack
        val l = locals
        try {
            while (true) {
                if (remaining <= 0) throw VmTrap(VmTrap.Kind.OUT_OF_FUEL)
                remaining--
                val op = c[ip]
                when (op) {
                    Op.CONST16 -> {
                        s[sp++] = ((c[ip + 1].toInt() shl 8) or (c[ip + 2].toInt() and 0xFF))
                        ip += 3
                    }
                    Op.CONST32 -> {
                        s[sp++] = (c[ip + 1].toInt() shl 24) or
                            ((c[ip + 2].toInt() and 0xFF) shl 16) or
                            ((c[ip + 3].toInt() and 0xFF) shl 8) or
                            (c[ip + 4].toInt() and 0xFF)
                        ip += 5
                    }
                    Op.ADD -> {
                        sp--
                        s[sp - 1] += s[sp]
                        ip++
                    }
                    Op.SUB -> {
                        sp--
                        s[sp - 1] -= s[sp]
                        ip++
                    }
                    Op.MUL -> {
                        sp--
                        s[sp - 1] *= s[sp]
                        ip++
                    }
                    Op.DIV -> {
                        sp--
                        if (s[sp] == 0) throw VmTrap(VmTrap.Kind.DIVIDE_BY_ZERO)
                        s[sp - 1] /= s[sp]
                        ip++
                    }
                    Op.REM -> {
                        sp--
                        if (s[sp] == 0) throw VmTrap(VmTrap.Kind.DIVIDE_BY_ZERO)
                        s[sp - 1] %= s[sp]
                        ip++
                    }
                    Op.AND -> {
                        sp--
                        s[sp - 1] = s[sp - 1] and s[sp]
                        ip++
                    }
                    Op.OR -> {
                        sp--
                        s[sp - 1] = s[sp - 1] or s[sp]
                        ip++
                    }
                    Op.XOR -> {
                        sp--
                        s[sp - 1] = s[sp - 1] xor s[sp]
                        ip++
                    }
                    Op.SHL -> {
                        sp--
                        s[sp - 1] = s[sp - 1] shl s[sp]
                        ip++
                    }
                    Op.SHR -> {
                        sp--
                        s[sp - 1] = s[sp - 1] shr s[sp]
                        ip++
                    }
                    Op.NEG -> {
                        s[sp - 1] = -s[sp - 1]
                        ip++
                    }
                    Op.LT -> {
                        sp--
                        s[sp - 1] = if (s[sp - 1] < s[sp]) 1 else 0
                        ip++
                    }
                    Op.EQ -> {
                        sp--
                        s[sp - 1] = if (s[sp - 1] == s[sp]) 1 else 0
                        ip++
                    }
                    Op.DUP -> {
                        s[sp] = s[sp - 1]
                        sp++
                        ip++
                    }
                    Op.DROP -> {
                        sp--
                        ip++
                    }
                    Op.SWAP -> {
                        val top = s[sp - 1]
                        s[sp - 1] = s[sp - 2]
                        s[sp - 2] = top
                        ip++
                    }
                    Op.LOAD -> {
                        s[sp++] = l[c[ip + 1].toInt() and 0xFF]
                        ip += 2
                    }
                    Op.STORE -> {
                        l[c[ip + 1].toInt() and 0xFF] = s[--sp]
                        ip += 2
                    }
                    Op.JMP -> ip = Verifier.readU16(c, ip + 1)
                    Op.JZ -> {
                        ip = if (s[--sp] == 0) Verifier.readU16(c, ip + 1) else ip + 3
                    }
                    Op.JNZ -> {
                        ip = if (s[--sp] != 0) Verifier.readU16(c, ip + 1) else ip + 3
                    }
                    Op.HALT -> return s[sp - 1]
                    else -> error("verified code held an unknown opcode")
                }
            }
        } finally {
            executed = if (fuel == Long.MAX_VALUE) Long.MAX_VALUE - remaining else fuel - remaining
        }
    }
}

class AssemblyError(val line: Int, message: String) : Exception("line $line: $message")

/**
 * Text assembler.
 *
 * ```text
 * start:          ; a label
 *     push 5      ; picks the shorter encoding automatically
 *     store 0
 *     load 0
 *     jnz start
 *     push 0
 *     halt
 * ```
 */
object Assembler {
    private val SIMPLE = mapOf(
        "add" to Op.ADD, "sub" to Op.SUB, "mul" to Op.MUL, "div" to Op.DIV, "rem" to Op.REM,
        "and" to Op.AND, "or" to Op.OR, "xor" to Op.XOR, "shl" to Op.SHL, "shr" to Op.SHR,
        "neg" to Op.NEG, "lt" to Op.LT, "eq" to Op.EQ, "dup" to Op.DUP, "drop" to Op.DROP,
        "swap" to Op.SWAP, "halt" to Op.HALT,
    )
    private val JUMPS = mapOf("jmp" to Op.JMP, "jz" to Op.JZ, "jnz" to Op.JNZ)

    fun assemble(source: String): ByteArray {
        data class Fixup(val at: Int, val label: String, val line: Int)

        val out = ArrayList<Byte>()
        val labels = HashMap<String, Int>()
        val fixups = ArrayList<Fixup>()
        for ((lineIndex, raw) in source.lineSequence().withIndex()) {
            val line = lineIndex + 1
            var text = raw.substringBefore(';').trim()
            while (':' in text) {
                val label = text.substringBefore(':').trim()
                if (label.isEmpty() || labels.put(label, out.size) != null) {
                    throw AssemblyError(line, "bad or duplicate label '$label'")
                }
                text = text.substringAfter(':').trim()
            }
            if (text.isEmpty()) continue
            val parts = text.split(Regex("\\s+"))
            val name = parts[0].lowercase()
            val argument = parts.getOrNull(1)
            if (parts.size > 2) throw AssemblyError(line, "too many operands")
            val operandMissing = { throw AssemblyError(line, "$name needs an operand") }
            when {
                name in SIMPLE -> {
                    if (argument != null) throw AssemblyError(line, "$name takes no operand")
                    out.add(SIMPLE.getValue(name))
                }
                name in JUMPS -> {
                    val target = argument ?: operandMissing()
                    out.add(JUMPS.getValue(name))
                    fixups.add(Fixup(out.size, target, line))
                    out.add(0)
                    out.add(0)
                }
                name == "push" -> {
                    val value = argument?.let { parseInt(it, line) } ?: operandMissing()
                    if (value in Short.MIN_VALUE..Short.MAX_VALUE) {
                        out.add(Op.CONST16)
                        out.add((value shr 8).toByte())
                        out.add(value.toByte())
                    } else {
                        out.add(Op.CONST32)
                        for (shift in intArrayOf(24, 16, 8, 0)) out.add((value shr shift).toByte())
                    }
                }
                name == "load" || name == "store" -> {
                    val slot = argument?.let { parseInt(it, line) } ?: operandMissing()
                    if (slot !in 0..255) throw AssemblyError(line, "local index out of range")
                    out.add(if (name == "load") Op.LOAD else Op.STORE)
                    out.add(slot.toByte())
                }
                else -> throw AssemblyError(line, "unknown instruction '$name'")
            }
        }
        if (out.size > 0xFFFF) throw AssemblyError(0, "program exceeds 64 KiB")
        for (fixup in fixups) {
            val target = labels[fixup.label]
                ?: throw AssemblyError(fixup.line, "undefined label '${fixup.label}'")
            out[fixup.at] = (target shr 8).toByte()
            out[fixup.at + 1] = target.toByte()
        }
        return out.toByteArray()
    }

    private fun parseInt(text: String, line: Int): Int =
        text.toLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            ?: throw AssemblyError(line, "invalid integer '$text'")
}
