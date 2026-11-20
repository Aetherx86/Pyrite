package dev.aetherx86.pyrite.vm

import java.util.IdentityHashMap

/** What [VmOptimizer.optimize] changed. */
data class OptimizationReport(
    val bytesBefore: Int,
    val bytesAfter: Int,
    val instructionsBefore: Int,
    val instructionsAfter: Int,
    /** Rounds of rewriting until nothing changed, including the final one that found nothing. */
    val rounds: Int,
)

class OptimizedProgram(val program: VerifiedProgram, val report: OptimizationReport)

/**
 * Rewrites verified bytecode into smaller verified bytecode with the same behavior.
 *
 * Each round decodes the reachable instructions, which drops dead code and uncalled functions,
 * applies local rewrites, re-encodes with fresh jump offsets and verifies the result again. Rounds
 * repeat until the code stops changing, so one rewrite can expose another.
 *
 * - Constant folding: `push 6; push 7; mul` becomes `push 42`. A division by a constant zero is
 *   left alone, so that it still traps.
 * - Branch folding: `push 0; jz L` becomes `jmp L`, and `push 1; jz L` disappears.
 * - Identities: `push 0; add`, `push 1; mul`, `push -1; and`, `dup; drop`, `swap; swap`,
 *   `neg; neg`, `load 3; drop` and similar pairs disappear.
 * - Jump threading: a jump to a `jmp` goes straight to its target, a `jmp` to the next
 *   instruction disappears, and a `jz` or `jnz` to the next instruction becomes `drop`.
 *
 * A rewrite never spans a jump target, except at its first instruction, whose label passes to
 * whatever replaces it. Results and traps are unchanged. The number of instructions executed is
 * smaller, so a fuel budget lasts longer.
 */
object VmOptimizer {
    private const val MAX_ROUNDS = 64

    private class Insn(var op: Byte, var operand: Int = 0, var target: Insn? = null)

    fun optimize(program: VerifiedProgram): OptimizedProgram {
        var current = program
        var rounds = 0
        while (rounds < MAX_ROUNDS) {
            rounds++
            val insns = decode(current)
            rewrite(insns)
            thread(insns)
            val bytes = encode(insns)
            if (bytes.contentEquals(current.code)) break
            current = try {
                Verifier.verify(bytes)
            } catch (e: VerifyError) {
                throw IllegalStateException("the optimizer produced code that fails verification", e)
            }
        }
        val report = OptimizationReport(
            bytesBefore = program.code.size,
            bytesAfter = current.code.size,
            instructionsBefore = program.instructionCount,
            instructionsAfter = current.instructionCount,
            rounds = rounds,
        )
        return OptimizedProgram(current, report)
    }

    /** The reachable instructions in code order. Fall-through stays intact, since the successor of a reachable instruction is reachable too. */
    private fun decode(program: VerifiedProgram): MutableList<Insn> {
        val code = program.code
        val byOffset = HashMap<Int, Insn>()
        val list = ArrayList<Insn>()
        var pc = 0
        while (pc < code.size) {
            val op = code[pc]
            if (program.isReachable(pc)) {
                val operand = when (op) {
                    Op.CONST16, Op.CONST32 -> Verifier.readConst(code, pc)
                    Op.LOAD, Op.STORE, Op.ENTER -> code[pc + 1].toInt() and 0xFF
                    else -> if (Op.isBranch(op)) Verifier.readU16(code, pc + 1) else 0
                }
                val insn = Insn(op, operand)
                byOffset[pc] = insn
                list.add(insn)
            }
            pc += Op.length(op)
        }
        for (insn in list) {
            if (Op.isBranch(insn.op)) insn.target = byOffset.getValue(insn.operand)
        }
        return list
    }

    private fun isPush(insn: Insn) = insn.op == Op.CONST16 || insn.op == Op.CONST32

    private fun fold(op: Byte, a: Int, b: Int): Int? = when (op) {
        Op.ADD -> a + b
        Op.SUB -> a - b
        Op.MUL -> a * b
        Op.DIV -> if (b == 0) null else a / b
        Op.REM -> if (b == 0) null else a % b
        Op.AND -> a and b
        Op.OR -> a or b
        Op.XOR -> a xor b
        Op.SHL -> a shl b
        Op.SHR -> a shr b
        Op.LT -> if (a < b) 1 else 0
        Op.EQ -> if (a == b) 1 else 0
        else -> null
    }

    /** True if `push k` followed by [op] leaves the value beneath unchanged. */
    private fun isIdentity(k: Int, op: Byte): Boolean = when (op) {
        Op.ADD, Op.SUB, Op.OR, Op.XOR -> k == 0
        Op.SHL, Op.SHR -> k and 31 == 0
        Op.MUL, Op.DIV -> k == 1
        Op.AND -> k == -1
        else -> false
    }

    private fun cancels(first: Byte, second: Byte): Boolean =
        (first == Op.DUP && second == Op.DROP) ||
            (first == Op.SWAP && second == Op.SWAP) ||
            (first == Op.NEG && second == Op.NEG) ||
            (first == Op.LOAD && second == Op.DROP)

    private fun rewrite(list: MutableList<Insn>) {
        val targeted = HashSet<Insn>().apply { list.forEach { it.target?.let(::add) } }
        // A removed instruction's label moves to the instruction that followed it.
        val forward = IdentityHashMap<Insn, Insn>()
        val out = ArrayList<Insn>(list.size)
        var i = 0

        /** Matches [count] instructions at i, none after the first being a jump target. */
        fun window(count: Int): List<Insn>? {
            if (i + count > list.size) return null
            val w = list.subList(i, i + count)
            for (k in 1 until count) if (w[k] in targeted) return null
            return w
        }

        fun drop(count: Int) {
            // A verified instruction that falls through always has a successor.
            val follower = list[i + count]
            for (k in 0 until count) forward[list[i + k]] = follower
            i += count
        }

        while (i < list.size) {
            val a = list[i]
            val three = window(3)
            if (three != null && isPush(three[0]) && isPush(three[1])) {
                val folded = fold(three[2].op, three[0].operand, three[1].operand)
                if (folded != null) {
                    a.operand = folded
                    a.op = Op.CONST32
                    // Revisit, since the folded constant may combine with what follows.
                    list.removeAt(i + 2)
                    list.removeAt(i + 1)
                    continue
                }
            }
            val two = window(2)
            if (two != null) {
                val b = two[1]
                when {
                    isPush(a) && b.op == Op.NEG -> {
                        a.operand = -a.operand
                        list.removeAt(i + 1)
                        continue
                    }
                    isPush(a) && (b.op == Op.JZ || b.op == Op.JNZ) -> {
                        val taken = (a.operand == 0) == (b.op == Op.JZ)
                        if (taken) {
                            a.op = Op.JMP
                            a.target = b.target
                            list.removeAt(i + 1)
                        } else {
                            drop(2)
                        }
                        continue
                    }
                    isPush(a) && isIdentity(a.operand, b.op) -> {
                        drop(2)
                        continue
                    }
                    isPush(a) && b.op == Op.DROP -> {
                        drop(2)
                        continue
                    }
                    cancels(a.op, b.op) -> {
                        drop(2)
                        continue
                    }
                }
            }
            if (a.op == Op.JMP && i + 1 < list.size && a.target === list[i + 1]) {
                drop(1)
                continue
            }
            if ((a.op == Op.JZ || a.op == Op.JNZ) && i + 1 < list.size && a.target === list[i + 1]) {
                a.op = Op.DROP
                a.target = null
            }
            out.add(a)
            i++
        }
        for (insn in out) {
            var t = insn.target ?: continue
            while (true) t = forward[t] ?: break
            insn.target = t
        }
        list.clear()
        list.addAll(out)
    }

    /** Points every jump at the end of any chain of `jmp` instructions it lands on. */
    private fun thread(list: List<Insn>) {
        for (insn in list) {
            if (insn.op != Op.JMP && insn.op != Op.JZ && insn.op != Op.JNZ) continue
            var t = insn.target!!
            val seen = HashSet<Insn>()
            while (t.op == Op.JMP && seen.add(t)) t = t.target!!
            insn.target = t
        }
    }

    private fun encodedLength(insn: Insn): Int = when (insn.op) {
        Op.CONST16, Op.CONST32 -> if (insn.operand in Short.MIN_VALUE..Short.MAX_VALUE) 3 else 5
        else -> Op.length(insn.op)
    }

    private fun encode(list: List<Insn>): ByteArray {
        val offsets = IdentityHashMap<Insn, Int>()
        var size = 0
        for (insn in list) {
            offsets[insn] = size
            size += encodedLength(insn)
        }
        val out = ByteArray(size)
        var pc = 0
        for (insn in list) {
            when {
                insn.op == Op.CONST16 || insn.op == Op.CONST32 -> {
                    val v = insn.operand
                    if (encodedLength(insn) == 3) {
                        out[pc] = Op.CONST16
                        out[pc + 1] = (v shr 8).toByte()
                        out[pc + 2] = v.toByte()
                    } else {
                        out[pc] = Op.CONST32
                        out[pc + 1] = (v shr 24).toByte()
                        out[pc + 2] = (v shr 16).toByte()
                        out[pc + 3] = (v shr 8).toByte()
                        out[pc + 4] = v.toByte()
                    }
                }
                Op.isBranch(insn.op) -> {
                    val target = offsets.getValue(insn.target!!)
                    out[pc] = insn.op
                    out[pc + 1] = (target shr 8).toByte()
                    out[pc + 2] = target.toByte()
                }
                insn.op == Op.LOAD || insn.op == Op.STORE || insn.op == Op.ENTER -> {
                    out[pc] = insn.op
                    out[pc + 1] = insn.operand.toByte()
                }
                else -> out[pc] = insn.op
            }
            pc += encodedLength(insn)
        }
        return out
    }
}
