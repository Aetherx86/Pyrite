package dev.aetherx86.pyrite.bf

import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream

/** What `,` does at the end of the input. */
enum class EofBehavior { UNCHANGED, ZERO, MINUS_ONE }

class OutOfFuel : Exception("the fuel budget is exhausted")

/** Raised when a program touches a cell outside the tape. */
class TapeOutOfBounds(val pointer: Int) : Exception("cell $pointer is outside the tape")

/**
 * Input, output and metering for a running program. Generated code calls these methods directly,
 * and they are small and final so that HotSpot inlines them.
 */
class BfIo(
    private val input: InputStream,
    output: OutputStream,
    private val eof: EofBehavior = EofBehavior.UNCHANGED,
    private var fuel: Long = Long.MAX_VALUE,
) {
    private val out = BufferedOutputStream(output, 8192)

    fun write(value: Int) {
        out.write(value)
    }

    /** Returns the byte to store, given what the cell currently holds. */
    fun read(current: Int): Int {
        val value = input.read()
        if (value >= 0) return value
        return when (eof) {
            EofBehavior.UNCHANGED -> current
            EofBehavior.ZERO -> 0
            EofBehavior.MINUS_ONE -> -1
        }
    }

    /** Charges one unit of fuel. Metered programs call this on every loop back-edge. */
    fun tick() {
        if (--fuel < 0) throw OutOfFuel()
    }

    fun flush() {
        out.flush()
    }
}

/** A program ready to run, on either backend. */
fun interface BfProgram {
    /** Runs on [tape], returning the final data pointer. */
    fun run(tape: ByteArray, io: BfIo): Int
}

const val DEFAULT_TAPE_SIZE = 30_000

/**
 * Plain character-at-a-time interpreter, kept deliberately simple. It defines the expected
 * behavior that the optimizer and both optimized backends are tested against.
 */
object ReferenceInterpreter {
    fun run(source: String, tape: ByteArray, io: BfIo, maxSteps: Long = Long.MAX_VALUE): Int {
        val jumps = IntArray(source.length)
        val stack = ArrayList<Int>()
        for ((i, c) in source.withIndex()) {
            if (c == '[') {
                stack.add(i)
            } else if (c == ']') {
                if (stack.isEmpty()) throw BfSyntaxError(i, "unmatched ]")
                val open = stack.removeAt(stack.size - 1)
                jumps[open] = i
                jumps[i] = open
            }
        }
        if (stack.isNotEmpty()) throw BfSyntaxError(stack.last(), "unmatched [")

        var pc = 0
        var p = 0
        var steps = 0L
        fun check(pointer: Int): Int {
            if (pointer !in tape.indices) throw TapeOutOfBounds(pointer)
            return pointer
        }
        try {
            while (pc < source.length) {
                if (++steps > maxSteps) throw OutOfFuel()
                when (source[pc]) {
                    '+' -> tape[check(p)]++
                    '-' -> tape[check(p)]--
                    '>' -> p++
                    '<' -> p--
                    '.' -> io.write(tape[check(p)].toInt())
                    ',' -> tape[check(p)] = io.read(tape[check(p)].toInt()).toByte()
                    '[' -> if (tape[check(p)] == 0.toByte()) pc = jumps[pc]
                    ']' -> if (tape[check(p)] != 0.toByte()) pc = jumps[pc]
                }
                pc++
            }
        } finally {
            io.flush()
        }
        return p
    }
}

/**
 * Threaded interpreter over the optimized IR. The IR is flattened into one `IntArray`, so the
 * dispatch loop allocates nothing and chases no pointers.
 */
class FlatInterpreter(nodes: List<Node>) : BfProgram {
    private companion object {
        const val ADD = 0
        const val SET = 1
        const val MOVE = 2
        const val OUT = 3
        const val IN = 4
        const val MUL_ENTER = 5
        const val MUL_TARGET = 6
        const val MUL_CLEAR = 7
        const val SCAN = 8
        const val JZ = 9
        const val JNZ = 10
        const val END = 11
    }

    private val code: IntArray

    init {
        val out = ArrayList<Int>()
        fun emit(list: List<Node>) {
            for (node in list) {
                when (node) {
                    is Node.Add -> out.addAll(listOf(ADD, node.offset, node.delta))
                    is Node.Set -> out.addAll(listOf(SET, node.offset, node.value))
                    is Node.Move -> out.addAll(listOf(MOVE, node.distance))
                    is Node.Output -> out.addAll(listOf(OUT, node.offset))
                    is Node.Input -> out.addAll(listOf(IN, node.offset))
                    is Node.Scan -> out.addAll(listOf(SCAN, node.stride))
                    is Node.MulLoop -> {
                        out.addAll(listOf(MUL_ENTER, 0))
                        val patch = out.size - 1
                        for (t in node.targets) out.addAll(listOf(MUL_TARGET, t.offset, t.factor))
                        out.add(MUL_CLEAR)
                        out[patch] = out.size
                    }
                    is Node.Loop -> {
                        out.addAll(listOf(JZ, 0))
                        val patch = out.size - 1
                        val bodyStart = out.size
                        emit(node.body)
                        out.addAll(listOf(JNZ, bodyStart))
                        out[patch] = out.size
                    }
                }
            }
        }
        emit(nodes)
        out.add(END)
        code = out.toIntArray()
    }

    override fun run(tape: ByteArray, io: BfIo): Int {
        val c = code
        var pc = 0
        var p = 0
        try {
            while (true) {
                when (c[pc]) {
                    ADD -> {
                        val i = p + c[pc + 1]
                        tape[i] = (tape[i] + c[pc + 2]).toByte()
                        pc += 3
                    }
                    SET -> {
                        tape[p + c[pc + 1]] = c[pc + 2].toByte()
                        pc += 3
                    }
                    MOVE -> {
                        p += c[pc + 1]
                        pc += 2
                    }
                    OUT -> {
                        io.write(tape[p + c[pc + 1]].toInt())
                        pc += 2
                    }
                    IN -> {
                        val i = p + c[pc + 1]
                        tape[i] = io.read(tape[i].toInt()).toByte()
                        pc += 2
                    }
                    SCAN -> {
                        val stride = c[pc + 1]
                        while (tape[p] != 0.toByte()) {
                            io.tick()
                            p += stride
                        }
                        pc += 2
                    }
                    MUL_ENTER -> {
                        pc = if (tape[p] == 0.toByte()) c[pc + 1] else pc + 2
                    }
                    MUL_TARGET -> {
                        val i = p + c[pc + 1]
                        tape[i] = (tape[i] + tape[p] * c[pc + 2]).toByte()
                        pc += 3
                    }
                    MUL_CLEAR -> {
                        tape[p] = 0
                        pc += 1
                    }
                    JZ -> {
                        pc = if (tape[p] == 0.toByte()) c[pc + 1] else pc + 2
                    }
                    JNZ -> {
                        if (tape[p] != 0.toByte()) {
                            io.tick()
                            pc = c[pc + 1]
                        } else {
                            pc += 2
                        }
                    }
                    else -> return p
                }
            }
        } catch (e: ArrayIndexOutOfBoundsException) {
            throw tapeError(e)
        } finally {
            io.flush()
        }
    }
}
