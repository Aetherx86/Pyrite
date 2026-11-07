package dev.aetherx86.pyrite.bf

import org.objectweb.asm.ClassTooLargeException
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodTooLargeException
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.IdentityHashMap

/**
 * Base class of generated programs. Translating failures here, rather than in generated code,
 * keeps the generated code small and gives both backends one definition of a tape error.
 */
abstract class CompiledProgram : BfProgram {
    final override fun run(tape: ByteArray, io: BfIo): Int {
        try {
            return execute(tape, io)
        } catch (e: ArrayIndexOutOfBoundsException) {
            throw tapeError(e)
        } finally {
            io.flush()
        }
    }

    protected abstract fun execute(tape: ByteArray, io: BfIo): Int
}

/** The JVM's message reads "Index 30000 out of bounds for length 30000". */
internal fun tapeError(e: ArrayIndexOutOfBoundsException): TapeOutOfBounds =
    TapeOutOfBounds(e.message?.let { Regex("-?\\d+").find(it)?.value?.toIntOrNull() } ?: -1)

/** The program does not fit in one JVM class. [FlatInterpreter] has no such limit. */
class BfTooLarge(message: String) : Exception(message)

/**
 * Compiles Brainfuck IR to JVM bytecode, loaded into a hidden class so that HotSpot's C2 turns
 * hot loops into machine code.
 *
 * A JVM method cannot exceed 64 KiB, and by default HotSpot refuses to compile any method over
 * 8000 bytes, leaving it interpreted forever. A one-method translation therefore runs large
 * programs far slower than their loops deserve, or fails to load at all. This compiler keeps
 * every method under [METHOD_BUDGET] bytes by outlining big loops and splitting long runs.
 */
object BfJit {
    private const val CLASS_NAME = "dev/aetherx86/pyrite/bf/CompiledBf"
    private const val SUPER_NAME = "dev/aetherx86/pyrite/bf/CompiledProgram"
    private const val IO = "dev/aetherx86/pyrite/bf/BfIo"

    /** Signature of generated methods: tape, pointer and I/O in, final pointer out. */
    private const val METHOD_DESC = "([BIL$IO;)I"

    /** Comfortably under HotSpot's 8000-byte limit, since costs below are only estimates. */
    internal const val METHOD_BUDGET = 6000

    /** Upper bound on the bytecode for a call to an outlined method. */
    private const val CALL_COST = 12

    /** How many pieces an oversized run splits into at each level of the method tree. */
    private const val FANOUT = METHOD_BUDGET / CALL_COST / 2

    private const val TAPE = 0
    private const val POINTER = 1
    private const val RUNTIME = 2

    /** Compiles IR. With [metered], every loop back-edge charges fuel from the runtime. */
    fun compile(nodes: List<Node>, metered: Boolean = false): BfProgram =
        Compilation(metered).compile(nodes)

    /** Number of methods a program compiles to, for tests that check the splitting works. */
    fun methodCount(nodes: List<Node>): Int = Compilation(false).also { it.compile(nodes) }.methodsEmitted

    private class Compilation(private val metered: Boolean) {
        private val costs = IdentityHashMap<Node, Int>()
        private val queue = ArrayDeque<Pair<String, List<Node>>>()
        private var nextId = 0
        var methodsEmitted = 0
            private set

        /** A conservative estimate of a node's bytecode size. */
        private fun cost(node: Node): Int = when (node) {
            is Node.Add -> 14
            is Node.Set -> 10
            is Node.Move -> 8
            is Node.Output -> 12
            is Node.Input -> 18
            is Node.Scan -> 24
            is Node.MulLoop -> 24 + 22 * node.targets.size
            is Node.Loop -> costs.getOrPut(node) {
                // Saturates, because a huge program's total could otherwise overflow.
                var sum = 32L
                for (child in node.body) sum += cost(child)
                sum.coerceAtMost(Int.MAX_VALUE / 4L).toInt()
            }
        }

        private fun outline(nodes: List<Node>): String {
            val name = "m${nextId++}"
            queue.addLast(name to nodes)
            return name
        }

        fun compile(nodes: List<Node>): BfProgram {
            val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
            cw.visit(
                Opcodes.V21,
                Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
                CLASS_NAME,
                null,
                SUPER_NAME,
                null,
            )
            cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, SUPER_NAME, "<init>", "()V", false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            cw.visitMethod(Opcodes.ACC_PROTECTED, "execute", "([BL$IO;)I", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 1)
                visitInsn(Opcodes.ICONST_0)
                visitVarInsn(Opcodes.ALOAD, 2)
                visitMethodInsn(Opcodes.INVOKESTATIC, CLASS_NAME, "main", METHOD_DESC, false)
                visitInsn(Opcodes.IRETURN)
                visitMaxs(0, 0)
                visitEnd()
            }

            // A work queue rather than recursion, so program size never touches the JVM stack.
            queue.addLast("main" to nodes)
            while (queue.isNotEmpty()) {
                val (name, body) = queue.removeFirst()
                val mv = cw.visitMethod(
                    Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC,
                    name,
                    METHOD_DESC,
                    null,
                    null,
                )
                mv.visitCode()
                emitMethodBody(mv, body)
                mv.visitVarInsn(Opcodes.ILOAD, POINTER)
                mv.visitInsn(Opcodes.IRETURN)
                mv.visitMaxs(0, 0)
                mv.visitEnd()
                methodsEmitted++
            }
            cw.visitEnd()

            val bytes = try {
                cw.toByteArray()
            } catch (e: ClassTooLargeException) {
                throw BfTooLarge("the program needs ${e.constantPoolCount} constant pool entries")
            } catch (e: MethodTooLargeException) {
                throw BfTooLarge("method ${e.methodName} needs ${e.codeSize} bytes")
            }
            val lookup = MethodHandles.lookup().defineHiddenClass(bytes, true)
            val constructor =
                lookup.findConstructor(lookup.lookupClass(), MethodType.methodType(Void.TYPE))
            return constructor.invoke() as BfProgram
        }

        /** Emits a whole method body. A lone loop stays here, with its body split up inside. */
        private fun emitMethodBody(mv: MethodVisitor, nodes: List<Node>) {
            val single = nodes.singleOrNull()
            if (single != null) emitNode(mv, single) else emitList(mv, nodes)
        }

        /** Emits nodes into the current method, outlining as needed to stay within budget. */
        private fun emitList(mv: MethodVisitor, nodes: List<Node>) {
            val oversized = { n: Node -> n is Node.Loop && cost(n) > METHOD_BUDGET / 2 }
            var total = 0L
            for (node in nodes) total += if (oversized(node)) CALL_COST else cost(node)

            if (total <= METHOD_BUDGET) {
                for (node in nodes) {
                    if (oversized(node)) {
                        emitCall(mv, outline(listOf(node)))
                    } else {
                        emitNode(mv, node)
                    }
                }
                return
            }

            // Too much for one method, so consecutive runs become helpers. Each helper comes
            // back through here, which builds a tree of small methods. Pieces are kept near half
            // a method, because every method uses up constant pool entries in the generated
            // class, and a class can only hold 65535 of them.
            val perPiece = maxOf(total / FANOUT + 1, METHOD_BUDGET / 2L)
            var piece = ArrayList<Node>()
            var pieceCost = 0L
            for (node in nodes) {
                piece.add(node)
                pieceCost += if (oversized(node)) CALL_COST else cost(node)
                if (pieceCost >= perPiece) {
                    emitCall(mv, outline(piece))
                    piece = ArrayList()
                    pieceCost = 0
                }
            }
            if (piece.isNotEmpty()) emitCall(mv, outline(piece))
        }

        private fun emitCall(mv: MethodVisitor, method: String) {
            mv.visitVarInsn(Opcodes.ALOAD, TAPE)
            mv.visitVarInsn(Opcodes.ILOAD, POINTER)
            mv.visitVarInsn(Opcodes.ALOAD, RUNTIME)
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, CLASS_NAME, method, METHOD_DESC, false)
            mv.visitVarInsn(Opcodes.ISTORE, POINTER)
        }

        private fun pushInt(mv: MethodVisitor, value: Int) {
            when (value) {
                in -1..5 -> mv.visitInsn(Opcodes.ICONST_0 + value)
                in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(Opcodes.BIPUSH, value)
                in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(Opcodes.SIPUSH, value)
                else -> mv.visitLdcInsn(value)
            }
        }

        /** Pushes the tape and the index `p + offset`. */
        private fun pushCell(mv: MethodVisitor, offset: Int) {
            mv.visitVarInsn(Opcodes.ALOAD, TAPE)
            mv.visitVarInsn(Opcodes.ILOAD, POINTER)
            if (offset != 0) {
                pushInt(mv, offset)
                mv.visitInsn(Opcodes.IADD)
            }
        }

        private fun emitMove(mv: MethodVisitor, distance: Int) {
            if (distance in Short.MIN_VALUE..Short.MAX_VALUE) {
                // IINC bumps the local in place, and ASM picks the wide form when it needs to.
                mv.visitIincInsn(POINTER, distance)
            } else {
                mv.visitVarInsn(Opcodes.ILOAD, POINTER)
                pushInt(mv, distance)
                mv.visitInsn(Opcodes.IADD)
                mv.visitVarInsn(Opcodes.ISTORE, POINTER)
            }
        }

        private fun emitTick(mv: MethodVisitor) {
            if (!metered) return
            mv.visitVarInsn(Opcodes.ALOAD, RUNTIME)
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IO, "tick", "()V", false)
        }

        private fun emitNode(mv: MethodVisitor, node: Node) {
            when (node) {
                is Node.Add -> {
                    pushCell(mv, node.offset)
                    mv.visitInsn(Opcodes.DUP2)
                    mv.visitInsn(Opcodes.BALOAD)
                    pushInt(mv, node.delta)
                    mv.visitInsn(Opcodes.IADD)
                    mv.visitInsn(Opcodes.I2B)
                    mv.visitInsn(Opcodes.BASTORE)
                }
                is Node.Set -> {
                    pushCell(mv, node.offset)
                    pushInt(mv, node.value)
                    mv.visitInsn(Opcodes.I2B)
                    mv.visitInsn(Opcodes.BASTORE)
                }
                is Node.Move -> emitMove(mv, node.distance)
                is Node.Output -> {
                    mv.visitVarInsn(Opcodes.ALOAD, RUNTIME)
                    pushCell(mv, node.offset)
                    mv.visitInsn(Opcodes.BALOAD)
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IO, "write", "(I)V", false)
                }
                is Node.Input -> {
                    // tape[i] = io.read(tape[i])
                    pushCell(mv, node.offset)
                    mv.visitInsn(Opcodes.DUP2)
                    mv.visitInsn(Opcodes.BALOAD)
                    mv.visitVarInsn(Opcodes.ALOAD, RUNTIME)
                    mv.visitInsn(Opcodes.SWAP)
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, IO, "read", "(I)I", false)
                    mv.visitInsn(Opcodes.I2B)
                    mv.visitInsn(Opcodes.BASTORE)
                }
                is Node.Scan -> {
                    val head = Label()
                    val done = Label()
                    mv.visitLabel(head)
                    pushCell(mv, 0)
                    mv.visitInsn(Opcodes.BALOAD)
                    mv.visitJumpInsn(Opcodes.IFEQ, done)
                    emitTick(mv)
                    emitMove(mv, node.stride)
                    mv.visitJumpInsn(Opcodes.GOTO, head)
                    mv.visitLabel(done)
                }
                is Node.MulLoop -> {
                    // The guard matters: a zero base means the loop never ran, so it never
                    // touched its targets, which may lie outside the tape.
                    val skip = Label()
                    pushCell(mv, 0)
                    mv.visitInsn(Opcodes.BALOAD)
                    mv.visitJumpInsn(Opcodes.IFEQ, skip)
                    for (target in node.targets) {
                        pushCell(mv, target.offset)
                        mv.visitInsn(Opcodes.DUP2)
                        mv.visitInsn(Opcodes.BALOAD)
                        pushCell(mv, 0)
                        mv.visitInsn(Opcodes.BALOAD)
                        pushInt(mv, target.factor)
                        mv.visitInsn(Opcodes.IMUL)
                        mv.visitInsn(Opcodes.IADD)
                        mv.visitInsn(Opcodes.I2B)
                        mv.visitInsn(Opcodes.BASTORE)
                    }
                    pushCell(mv, 0)
                    mv.visitInsn(Opcodes.ICONST_0)
                    mv.visitInsn(Opcodes.BASTORE)
                    mv.visitLabel(skip)
                }
                is Node.Loop -> {
                    val top = Label()
                    val done = Label()
                    pushCell(mv, 0)
                    mv.visitInsn(Opcodes.BALOAD)
                    mv.visitJumpInsn(Opcodes.IFEQ, done)
                    mv.visitLabel(top)
                    emitList(mv, node.body)
                    emitTick(mv)
                    pushCell(mv, 0)
                    mv.visitInsn(Opcodes.BALOAD)
                    mv.visitJumpInsn(Opcodes.IFNE, top)
                    mv.visitLabel(done)
                }
            }
        }
    }
}
