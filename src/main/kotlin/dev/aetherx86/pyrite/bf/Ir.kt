package dev.aetherx86.pyrite.bf

/**
 * Brainfuck intermediate representation. Every cell access is relative to the data pointer, so
 * pointer movement around a group of operations folds away entirely.
 */
sealed interface Node {
    /** `tape[p + offset] += delta` */
    data class Add(val offset: Int, val delta: Int) : Node

    /** `tape[p + offset] = value` */
    data class Set(val offset: Int, val value: Int) : Node

    /** `p += distance` */
    data class Move(val distance: Int) : Node

    /** Writes `tape[p + offset]` to the output. */
    data class Output(val offset: Int) : Node

    /** Reads one byte into `tape[p + offset]`. */
    data class Input(val offset: Int) : Node

    /**
     * A loop that moves nothing on net and steps its base cell by -1, such as `[->++<]`. Running
     * it `tape[p]` times is the same as adding `tape[p] * factor` to each target, then clearing
     * the base. When `tape[p]` is zero it does nothing, and in particular it touches no targets.
     */
    data class MulLoop(val targets: List<Target>) : Node {
        data class Target(val offset: Int, val factor: Int)
    }

    /** `while (tape[p] != 0) p += stride`, from loops such as `[>]` and `[<<]`. */
    data class Scan(val stride: Int) : Node

    data class Loop(val body: List<Node>) : Node
}

class BfSyntaxError(val position: Int, message: String) : Exception("at position $position: $message")

object Parser {
    /** Bounds recursion in every later pass, so hostile nesting cannot overflow the stack. */
    const val MAX_NESTING = 1000

    /** Parses source text without optimizing it. Characters outside the language are comments. */
    fun parse(source: String): List<Node> {
        val stack = ArrayList<ArrayList<Node>>()
        val openedAt = ArrayList<Int>()
        var current = ArrayList<Node>()
        for ((i, c) in source.withIndex()) {
            when (c) {
                '+' -> current.add(Node.Add(0, 1))
                '-' -> current.add(Node.Add(0, -1))
                '>' -> current.add(Node.Move(1))
                '<' -> current.add(Node.Move(-1))
                '.' -> current.add(Node.Output(0))
                ',' -> current.add(Node.Input(0))
                '[' -> {
                    if (stack.size >= MAX_NESTING) {
                        throw BfSyntaxError(i, "loops are nested more than $MAX_NESTING levels deep")
                    }
                    stack.add(current)
                    openedAt.add(i)
                    current = ArrayList()
                }
                ']' -> {
                    if (stack.isEmpty()) throw BfSyntaxError(i, "unmatched ]")
                    val body = current
                    current = stack.removeAt(stack.size - 1)
                    openedAt.removeAt(openedAt.size - 1)
                    current.add(Node.Loop(body))
                }
            }
        }
        if (stack.isNotEmpty()) throw BfSyntaxError(openedAt.last(), "unmatched [")
        return current
    }
}

/**
 * Peephole optimizer. Each pass preserves the order of cell accesses, so a program that runs
 * off the tape still produces the same output before failing, whether or not it is optimized.
 */
object Optimizer {
    private const val MAX_MUL_TARGETS = 64

    fun optimize(nodes: List<Node>): List<Node> {
        val out = ArrayList<Node>(nodes.size)
        // The offset that pending pointer movement has accumulated, not yet emitted as a Move.
        var offset = 0

        fun flushMove() {
            if (offset != 0) {
                out.add(Node.Move(offset))
                offset = 0
            }
        }

        for (node in nodes) {
            when (node) {
                is Node.Move -> offset = addChecked(offset, node.distance)
                is Node.Add -> addTo(out, offset + node.offset, node.delta)
                is Node.Set -> setTo(out, offset + node.offset, node.value)
                is Node.Output -> out.add(Node.Output(offset + node.offset))
                is Node.Input -> {
                    out.add(Node.Input(offset + node.offset))
                }
                is Node.Loop -> {
                    flushMove()
                    out.add(optimizeLoop(node))
                }
                is Node.MulLoop, is Node.Scan -> {
                    flushMove()
                    out.add(node)
                }
            }
        }
        flushMove()
        return out
    }

    private fun addChecked(a: Int, b: Int): Int = Math.addExact(a, b)

    private fun addTo(out: MutableList<Node>, offset: Int, delta: Int) {
        val last = out.lastOrNull()
        when {
            last is Node.Add && last.offset == offset -> {
                out[out.size - 1] = Node.Add(offset, last.delta + delta)
            }
            // Adding to a known value yields a known value.
            last is Node.Set && last.offset == offset -> {
                out[out.size - 1] = Node.Set(offset, (last.value + delta) and 0xFF)
            }
            else -> out.add(Node.Add(offset, delta))
        }
        // An add that nets out to zero stays in the list. It still touches the cell, so removing
        // it would let a program carry on where the unoptimized one fails with a tape error.
    }

    private fun setTo(out: MutableList<Node>, offset: Int, value: Int) {
        val last = out.lastOrNull()
        // A write to a cell makes an immediately preceding write to it pointless.
        if ((last is Node.Add && last.offset == offset) || (last is Node.Set && last.offset == offset)) {
            out.removeAt(out.size - 1)
        }
        out.add(Node.Set(offset, value))
    }

    private fun optimizeLoop(loop: Node.Loop): Node {
        val body = optimize(loop.body)

        val only = body.singleOrNull()
        // [-] and [+] and any other odd step reach zero after wrapping, so they just clear the
        // cell. An even step might never get there, so those stay as real loops.
        if (only is Node.Add && only.offset == 0 && only.delta and 1 == 1) {
            return Node.Set(0, 0)
        }
        if (only is Node.Move) return Node.Scan(only.distance)

        analyzeMulLoop(body)?.let { return it }
        return Node.Loop(body)
    }

    private fun analyzeMulLoop(body: List<Node>): Node? {
        if (body.isEmpty()) return null
        val deltas = LinkedHashMap<Int, Int>()
        for (node in body) {
            if (node !is Node.Add) return null
            deltas[node.offset] = (deltas[node.offset] ?: 0) + node.delta
        }
        if ((deltas[0] ?: 0) and 0xFF != 0xFF) return null
        val targets = deltas.filterKeys { it != 0 }
            .filterValues { it and 0xFF != 0 }
            .map { (offset, factor) -> Node.MulLoop.Target(offset, factor) }
        // A multiply loop compiles to one indivisible block. A very wide one stays an ordinary
        // loop, whose body can be split across methods.
        if (targets.size > MAX_MUL_TARGETS) return null
        return Node.MulLoop(targets)
    }
}
