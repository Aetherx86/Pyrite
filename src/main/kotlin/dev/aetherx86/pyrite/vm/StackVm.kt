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

    /** Calls the function whose `ENTER` is at the 16-bit target, and pushes its result. */
    const val CALL: Byte = 0x19

    /** Marks a function entry. The 8-bit operand is the number of arguments it takes. */
    const val ENTER: Byte = 0x1A

    /** Returns the single value on a function's operand stack to its caller. */
    const val RET: Byte = 0x1B

    /** Encoded length of an instruction, or -1 for an unknown opcode. */
    fun length(op: Byte): Int = when (op) {
        CONST16, JMP, JZ, JNZ, CALL -> 3
        CONST32 -> 5
        LOAD, STORE, ENTER -> 2
        ADD, SUB, MUL, DIV, REM, AND, OR, XOR, SHL, SHR, NEG, LT, EQ, DUP, DROP, SWAP, HALT, RET -> 1
        else -> -1
    }

    /**
     * Stack effect as (values popped, values pushed). A `CALL` also pops the callee's arguments,
     * which only the verifier knows, since they depend on the target.
     */
    fun effect(op: Byte): Pair<Int, Int> = when (op) {
        CONST16, CONST32, LOAD, CALL -> 0 to 1
        ADD, SUB, MUL, DIV, REM, AND, OR, XOR, SHL, SHR, LT, EQ -> 2 to 1
        NEG -> 1 to 1
        DUP -> 1 to 2
        DROP, STORE, JZ, JNZ -> 1 to 0
        SWAP -> 2 to 2
        HALT, RET -> 1 to 0
        else -> 0 to 0
    }

    /** Assembler name of an opcode, or null for an unknown one. */
    fun mnemonic(op: Byte): String? = when (op) {
        CONST16, CONST32 -> "push"
        ADD -> "add"
        SUB -> "sub"
        MUL -> "mul"
        DIV -> "div"
        REM -> "rem"
        AND -> "and"
        OR -> "or"
        XOR -> "xor"
        SHL -> "shl"
        SHR -> "shr"
        NEG -> "neg"
        LT -> "lt"
        EQ -> "eq"
        DUP -> "dup"
        DROP -> "drop"
        SWAP -> "swap"
        LOAD -> "load"
        STORE -> "store"
        JMP -> "jmp"
        JZ -> "jz"
        JNZ -> "jnz"
        HALT -> "halt"
        CALL -> "call"
        ENTER -> "enter"
        RET -> "ret"
        else -> null
    }

    /** True for instructions whose operand is a code offset. */
    fun isBranch(op: Byte): Boolean = op == JMP || op == JZ || op == JNZ || op == CALL
}

class VerifyError(val offset: Int, message: String) : Exception("at offset $offset: $message")

class VmTrap(val kind: Kind) : Exception(kind.name) {
    enum class Kind { DIVIDE_BY_ZERO, OUT_OF_FUEL, CALL_DEPTH_EXCEEDED }
}

/** What the verifier proved about one function. */
data class FunctionInfo(
    /** Offset of the function's `ENTER` instruction, which is also what `CALL` targets. */
    val entry: Int,
    /** Number of arguments, which arrive in locals `0 until argc`. */
    val argc: Int,
    /** Locals the function's frame needs: its arguments and every local it touches. */
    val localCount: Int,
    /** Deepest operand stack the function's own frame reaches. */
    val maxStack: Int,
)

/** Bytecode that has passed verification. The only way to obtain one is [Verifier.verify]. */
class VerifiedProgram internal constructor(
    val code: ByteArray,
    /** Deepest operand stack any single frame reaches. */
    val maxStack: Int,
    internal val instructionStarts: BooleanArray,
    private val depthAt: IntArray,
    private val ownerAt: IntArray,
    /** Every function reachable from the main program, by entry offset. */
    val functions: List<FunctionInfo>,
) {
    /** Number of locals the main program can touch. */
    val localCount: Int = 256

    internal val frameLocals = IntArray(code.size)
    internal val frameStack = IntArray(code.size)

    init {
        for (f in functions) {
            frameLocals[f.entry] = f.localCount
            frameStack[f.entry] = f.maxStack
        }
    }

    /** True if [offset] is the first byte of an instruction. */
    fun isInstructionStart(offset: Int): Boolean = offset in code.indices && instructionStarts[offset]

    /** True if some path from the start of the program executes the instruction at [offset]. */
    fun isReachable(offset: Int): Boolean = offset in code.indices && depthAt[offset] >= 0

    /**
     * Operand stack depth on entry to the instruction at [offset], counted within its own frame,
     * or -1 if the instruction is unreachable.
     */
    fun stackDepthAt(offset: Int): Int = if (offset in code.indices) depthAt[offset] else -1

    /**
     * Entry offset of the function that owns the instruction at [offset], 0 for the main program
     * and -1 for unreachable code. The verifier proves that each instruction has one owner.
     */
    fun ownerOf(offset: Int): Int = if (offset in code.indices) ownerAt[offset] else -1

    /** Number of instructions, reachable or not. */
    val instructionCount: Int = instructionStarts.count { it }

    /** Number of instructions that some path executes. */
    val reachableInstructionCount: Int = code.indices.count { instructionStarts[it] && depthAt[it] >= 0 }
}

/**
 * Proves a program safe before it runs, in the style of the JVM's own verifier.
 *
 * Verified code cannot underflow or overflow the stack, cannot jump into the middle of an
 * instruction, cannot run off the end of the code, and reaches each instruction with one
 * consistent stack depth. That last property is what allows direct translation to JVM bytecode.
 *
 * Functions are proved separately. Each starts with an empty operand stack, receives its
 * arguments in locals, is entered only through `CALL`, and returns with `RET` holding exactly
 * one value. Every instruction belongs to exactly one function or to the main program, so no
 * jump crosses from one to another and each one translates to its own JVM method.
 */
object Verifier {
    const val MAX_STACK = 1024

    /** A JVM method takes at most 255 parameter slots, and the JIT adds one for the call depth. */
    const val MAX_ARGS = 254

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
        if (lastOp != Op.HALT && lastOp != Op.JMP && lastOp != Op.RET) {
            throw VerifyError(code.size, "execution can run off the end of the program")
        }

        val depthAt = IntArray(code.size) { -1 }
        val ownerAt = IntArray(code.size) { -1 }
        val work = ArrayDeque<Int>()
        val maxStackOf = HashMap<Int, Int>()
        val localsOf = HashMap<Int, Int>()

        fun describe(owner: Int) = if (owner == 0) "the main program" else "the function at $owner"

        fun flow(target: Int, depth: Int, from: Int, owner: Int) {
            if (target < 0 || target >= code.size || !starts[target]) {
                throw VerifyError(from, "invalid jump target $target")
            }
            if (code[target] == Op.ENTER) {
                throw VerifyError(from, "control reaches the function at $target without a call")
            }
            val current = ownerAt[target]
            if (current != -1 && current != owner) {
                throw VerifyError(from, "control flows from ${describe(owner)} into ${describe(current)}")
            }
            when (depthAt[target]) {
                -1 -> {
                    depthAt[target] = depth
                    ownerAt[target] = owner
                    work.addLast(target)
                }
                depth -> {}
                else -> throw VerifyError(
                    target,
                    "inconsistent stack depth: ${depthAt[target]} and $depth",
                )
            }
        }

        fun enterFunction(entry: Int, from: Int): Int {
            if (entry >= code.size || !starts[entry] || code[entry] != Op.ENTER) {
                throw VerifyError(from, "call target $entry is not a function entry")
            }
            val argc = code[entry + 1].toInt() and 0xFF
            if (argc > MAX_ARGS) throw VerifyError(entry, "a function takes at most $MAX_ARGS arguments")
            if (ownerAt[entry] == -1) {
                ownerAt[entry] = entry
                depthAt[entry] = 0
                localsOf[entry] = argc
                maxStackOf[entry] = 0
                flow(entry + Op.length(Op.ENTER), 0, entry, entry)
            }
            return argc
        }

        localsOf[0] = 0
        maxStackOf[0] = 0
        flow(0, 0, 0, 0)
        while (work.isNotEmpty()) {
            val at = work.removeFirst()
            val op = code[at]
            val depth = depthAt[at]
            val owner = ownerAt[at]
            val next = at + Op.length(op)
            when (op) {
                Op.HALT -> if (owner != 0) throw VerifyError(at, "halt inside ${describe(owner)}")
                Op.RET -> {
                    if (owner == 0) throw VerifyError(at, "ret outside a function")
                    if (depth != 1) {
                        throw VerifyError(at, "ret needs exactly one value on the stack, found $depth")
                    }
                }
            }
            val after = if (op == Op.CALL) {
                val argc = enterFunction(readU16(code, at + 1), at)
                if (depth < argc) throw VerifyError(at, "stack underflow")
                depth - argc + 1
            } else {
                val (pops, pushes) = Op.effect(op)
                if (depth < pops) throw VerifyError(at, "stack underflow")
                depth - pops + pushes
            }
            if (after > MAX_STACK) throw VerifyError(at, "stack overflow")
            maxStackOf[owner] = maxOf(maxStackOf.getValue(owner), after, depth)
            if (op == Op.LOAD || op == Op.STORE) {
                localsOf[owner] = maxOf(localsOf.getValue(owner), (code[at + 1].toInt() and 0xFF) + 1)
            }
            when (op) {
                Op.HALT, Op.RET -> {}
                Op.JMP -> flow(readU16(code, at + 1), after, at, owner)
                Op.JZ, Op.JNZ -> {
                    flow(readU16(code, at + 1), after, at, owner)
                    flow(next, after, at, owner)
                }
                else -> flow(next, after, at, owner)
            }
        }
        val functions = localsOf.keys.filter { it != 0 }.sorted().map { entry ->
            FunctionInfo(
                entry = entry,
                argc = code[entry + 1].toInt() and 0xFF,
                localCount = localsOf.getValue(entry),
                maxStack = maxStackOf.getValue(entry),
            )
        }
        return VerifiedProgram(code, maxOf(maxStackOf.values.max(), 1), starts, depthAt, ownerAt, functions)
    }

    internal fun readU16(code: ByteArray, at: Int): Int =
        ((code[at].toInt() and 0xFF) shl 8) or (code[at + 1].toInt() and 0xFF)

    internal fun readConst(code: ByteArray, at: Int): Int = when (code[at]) {
        Op.CONST16 -> (code[at + 1].toInt() shl 8) or (code[at + 2].toInt() and 0xFF)
        else -> (code[at + 1].toInt() shl 24) or
            ((code[at + 2].toInt() and 0xFF) shl 16) or
            ((code[at + 3].toInt() and 0xFF) shl 8) or
            (code[at + 4].toInt() and 0xFF)
    }
}

/**
 * Zero-boxing bytecode interpreter. Each instance owns its stack and locals, and is not safe to
 * share between threads.
 *
 * Function frames live in the same two arrays as the main program. A call moves its arguments
 * into a fresh window of locals and starts the callee's operand stack where the arguments were.
 * Since a verified `RET` leaves exactly one value, that value is already where the caller wants
 * its result. The arrays grow on first use and are reused afterwards, so a warmed-up run
 * allocates nothing.
 */
class StackVm(private val program: VerifiedProgram) {
    companion object {
        /** Deepest chain of active calls. One more traps with [VmTrap.Kind.CALL_DEPTH_EXCEEDED]. */
        const val MAX_CALL_DEPTH = 512
    }

    private val code = program.code
    private var stack = IntArray(program.maxStack + 2)
    private var locals = IntArray(program.localCount)

    /** Return address, caller's locals base and caller's frame size, for each active call. */
    private val frames = IntArray(if (program.functions.isEmpty()) 0 else MAX_CALL_DEPTH * 3)

    /** Number of instructions executed by the last [run]. */
    var executed = 0L
        private set

    /** Runs until `HALT` and returns the top of the stack. */
    fun run(fuel: Long = Long.MAX_VALUE): Int {
        locals.fill(0, 0, program.localCount)
        var sp = 0
        var ip = 0
        var lb = 0
        var frameSize = program.localCount
        var depth = 0
        var remaining = fuel
        val c = code
        var s = stack
        var l = locals
        val f = frames
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
                        s[sp++] = l[lb + (c[ip + 1].toInt() and 0xFF)]
                        ip += 2
                    }
                    Op.STORE -> {
                        l[lb + (c[ip + 1].toInt() and 0xFF)] = s[--sp]
                        ip += 2
                    }
                    Op.JMP -> ip = Verifier.readU16(c, ip + 1)
                    Op.JZ -> {
                        ip = if (s[--sp] == 0) Verifier.readU16(c, ip + 1) else ip + 3
                    }
                    Op.JNZ -> {
                        ip = if (s[--sp] != 0) Verifier.readU16(c, ip + 1) else ip + 3
                    }
                    Op.CALL -> {
                        if (depth == MAX_CALL_DEPTH) throw VmTrap(VmTrap.Kind.CALL_DEPTH_EXCEEDED)
                        val target = Verifier.readU16(c, ip + 1)
                        val argc = c[target + 1].toInt() and 0xFF
                        val calleeLocals = program.frameLocals[target]
                        val base = sp - argc
                        val newLb = lb + frameSize
                        if (newLb + calleeLocals > l.size) {
                            l = l.copyOf(maxOf(l.size * 2, newLb + calleeLocals))
                            locals = l
                        }
                        if (base + program.frameStack[target] + 2 > s.size) {
                            s = s.copyOf(maxOf(s.size * 2, base + program.frameStack[target] + 2))
                            stack = s
                        }
                        System.arraycopy(s, base, l, newLb, argc)
                        l.fill(0, newLb + argc, newLb + calleeLocals)
                        val frame = depth * 3
                        f[frame] = ip + 3
                        f[frame + 1] = lb
                        f[frame + 2] = frameSize
                        depth++
                        lb = newLb
                        frameSize = calleeLocals
                        sp = base
                        ip = target + 2
                    }
                    Op.RET -> {
                        // The result is the only value in the frame, so it already sits where
                        // the arguments began, which is where the caller expects it.
                        depth--
                        val frame = depth * 3
                        ip = f[frame]
                        lb = f[frame + 1]
                        frameSize = f[frame + 2]
                    }
                    Op.HALT -> return s[sp - 1]
                    else -> error("verified code reached opcode $op")
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
 *
 * Functions are labels on an `enter` instruction, whose operand is the number of arguments:
 *
 * ```text
 *     push 6
 *     call square
 *     halt
 * square:
 *     enter 1     ; the argument arrives in local 0
 *     load 0
 *     dup
 *     mul
 *     ret
 * ```
 */
object Assembler {
    private val SIMPLE = mapOf(
        "add" to Op.ADD, "sub" to Op.SUB, "mul" to Op.MUL, "div" to Op.DIV, "rem" to Op.REM,
        "and" to Op.AND, "or" to Op.OR, "xor" to Op.XOR, "shl" to Op.SHL, "shr" to Op.SHR,
        "neg" to Op.NEG, "lt" to Op.LT, "eq" to Op.EQ, "dup" to Op.DUP, "drop" to Op.DROP,
        "swap" to Op.SWAP, "halt" to Op.HALT, "ret" to Op.RET,
    )
    private val JUMPS = mapOf("jmp" to Op.JMP, "jz" to Op.JZ, "jnz" to Op.JNZ, "call" to Op.CALL)

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
                name == "enter" -> {
                    val argc = argument?.let { parseInt(it, line) } ?: operandMissing()
                    if (argc !in 0..Verifier.MAX_ARGS) throw AssemblyError(line, "argument count out of range")
                    out.add(Op.ENTER)
                    out.add(argc.toByte())
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
