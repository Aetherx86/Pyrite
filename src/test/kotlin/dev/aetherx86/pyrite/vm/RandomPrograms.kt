package dev.aetherx86.pyrite.vm

import java.util.Random

/**
 * Generators of valid random programs for differential tests. Each one tracks the stack depth
 * the way the verifier does, so everything it builds verifies. With [foldable] off, everything it
 * builds is also reachable, so it reassembles from disassembly byte for byte.
 */
object RandomPrograms {
    private val BINARY = listOf("add", "sub", "mul", "and", "or", "xor", "shl", "shr", "lt", "eq", "div", "rem")

    /**
     * A counted loop whose body mixes arithmetic with calls to a few functions, and with the
     * redundant and constant patterns that the optimizer rewrites. Function k only calls
     * functions below k, so every program terminates. Some programs trap on division by zero.
     */
    fun generate(random: Random, functions: Int = random.nextInt(4), foldable: Boolean = true): String {
        val arities = IntArray(functions) { random.nextInt(4) }
        var labelId = 0
        return buildString {
            appendLine("push ${random.nextInt(25)}")
            appendLine("store 0")
            appendLine("top:")
            appendLine("load 0")
            appendLine("jz done")
            body(this, random, locals = 1..4, callable = functions, arities = arities, foldable = foldable) {
                "m${labelId++}"
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
            for (k in 0 until functions) {
                appendLine("f$k:")
                appendLine("enter ${arities[k]}")
                // Locals beyond the arguments start at zero on every call.
                body(this, random, locals = 0..5, callable = k, arities = arities, foldable = foldable) {
                    "f${k}x${labelId++}"
                }
                appendLine("load ${random.nextInt(6)}")
                appendLine("ret")
            }
        }
    }

    /** Appends a depth-neutral run of instructions that stores whatever it computes. */
    private fun body(
        sb: StringBuilder,
        random: Random,
        locals: IntRange,
        callable: Int,
        arities: IntArray,
        foldable: Boolean,
        newLabel: () -> String,
    ) {
        fun local() = locals.first + random.nextInt(locals.last - locals.first + 1)
        var depth = 0
        repeat(5 + random.nextInt(30)) {
            when {
                depth < 2 || random.nextInt(4) == 0 -> {
                    if (random.nextBoolean()) {
                        sb.appendLine("push ${interesting(random)}")
                    } else {
                        sb.appendLine("load ${local()}")
                    }
                    depth++
                }
                callable > 0 && random.nextInt(5) == 0 -> {
                    val f = random.nextInt(callable)
                    if (depth >= arities[f]) {
                        sb.appendLine("call f$f")
                        depth += 1 - arities[f]
                    }
                }
                foldable && random.nextInt(5) == 0 -> sb.appendLine(
                    listOf(
                        "push 0\nadd", "push 0\nsub", "push 1\nmul", "push 1\ndiv", "push -1\nand",
                        "push 0\nor", "push 0\nxor", "push 32\nshl", "dup\ndrop", "swap\nswap",
                        "neg\nneg", "push 7\ndrop", "push ${interesting(random)}\nneg\nadd",
                        "push ${interesting(random)}\npush ${interesting(random)}\n${BINARY.random(random)}\nxor",
                    ).random(random),
                )
                foldable && random.nextInt(6) == 0 -> {
                    // A branch on a constant, or a jump to the very next instruction.
                    val label = newLabel()
                    when (random.nextInt(3)) {
                        0 -> sb.appendLine("push ${random.nextInt(2)}\n${if (random.nextBoolean()) "jz" else "jnz"} $label")
                        1 -> sb.appendLine("jmp $label")
                        else -> sb.appendLine("dup\njnz $label")
                    }
                    sb.appendLine("load ${local()}\nneg\nstore ${local()}")
                    sb.appendLine("$label:")
                }
                random.nextInt(6) == 0 -> sb.appendLine("dup").also { depth++ }
                random.nextInt(6) == 0 -> sb.appendLine("swap")
                random.nextInt(6) == 0 -> sb.appendLine("neg")
                random.nextInt(6) == 0 -> {
                    sb.appendLine("store ${local()}")
                    depth--
                }
                else -> {
                    sb.appendLine(BINARY.random(random))
                    depth--
                }
            }
        }
        repeat(depth) { sb.appendLine("store ${local()}") }
    }

    private fun interesting(random: Random): Int = when (random.nextInt(6)) {
        0 -> 0
        1 -> 1
        2 -> -1
        3 -> random.nextInt(64)
        else -> random.nextInt()
    }

    private fun <T> List<T>.random(random: Random): T = this[random.nextInt(size)]
}
