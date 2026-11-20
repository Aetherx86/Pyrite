package dev.aetherx86.pyrite.vm

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodTooLargeException
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/** A compiled program. Implementations are generated at run time. */
fun interface VmProgram {
    fun run(): Int
}

class JitUnavailable(message: String) : Exception(message)

/**
 * Translates verified bytecode to JVM bytecode, so that HotSpot compiles it to machine code.
 *
 * The VM's operand stack maps directly onto the JVM's. This is sound because verification
 * proved that each instruction is always reached with the same stack depth, and that depth is
 * what the JVM's own verifier checks. No stack array exists in the generated code, so C2 sees
 * plain integer locals and optimizes them freely.
 *
 * Each verified function becomes a private static JVM method whose parameters are its
 * arguments and the call depth, so a VM call is one `invokestatic` that C2 can inline.
 */
object VmJit {
    private const val CLASS_NAME = "dev/aetherx86/pyrite/vm/CompiledVm"

    /** Compiles a program. The class is hidden, so it unloads once unreferenced. */
    fun compile(program: VerifiedProgram): VmProgram {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(
            Opcodes.V21,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            CLASS_NAME,
            null,
            "java/lang/Object",
            arrayOf(VmProgram::class.java.name.replace('.', '/')),
        )
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "run", "()I", null, null).apply {
            visitCode()
            emitBody(this, program, Frame.MAIN)
            visitMaxs(0, 0)
            visitEnd()
        }
        for (function in program.functions) {
            cw.visitMethod(
                Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC,
                methodName(function.entry),
                descriptor(function.argc),
                null,
                null,
            ).apply {
                visitCode()
                emitBody(this, program, Frame(function.entry, function.argc))
                visitMaxs(0, 0)
                visitEnd()
            }
        }
        cw.visitEnd()

        val bytes = try {
            cw.toByteArray()
        } catch (e: MethodTooLargeException) {
            throw JitUnavailable("the program is too large for one JVM method (${e.codeSize} bytes)")
        }
        val lookup = MethodHandles.lookup().defineHiddenClass(bytes, true)
        val constructor = lookup.findConstructor(lookup.lookupClass(), MethodType.methodType(Void.TYPE))
        val compiled = constructor.invoke() as VmProgram
        if (program.functions.isEmpty()) return compiled
        // Calls nest on the JVM stack. The depth limit matches the interpreter's, and this only
        // catches a thread whose own stack is too small to reach it.
        return VmProgram {
            try {
                compiled.run()
            } catch (e: StackOverflowError) {
                throw VmTrap(VmTrap.Kind.CALL_DEPTH_EXCEEDED)
            }
        }
    }

    private fun methodName(entry: Int) = "f$entry"

    /** Arguments, then the depth of the frame being entered. */
    private fun descriptor(argc: Int) = "(" + "I".repeat(argc) + "I)I"

    /**
     * Where one VM frame keeps its values in a JVM method. The main program is the instance
     * method `run`, so `this` takes slot 0 and local k lives in slot k + 1. A function is a
     * static method whose parameters are its arguments, which are also its first locals, then
     * the call depth.
     */
    private class Frame(val owner: Int, val argc: Int) {
        val isMain get() = owner == 0
        val depthSlot get() = argc

        fun slotOf(local: Int): Int = when {
            isMain -> local + 1
            local < argc -> local
            else -> local + 1
        }

        companion object {
            val MAIN = Frame(0, 0)
        }
    }

    private fun emitBody(mv: MethodVisitor, program: VerifiedProgram, frame: Frame) {
        val code = program.code
        if (!frame.isMain) {
            val ok = Label()
            mv.visitVarInsn(Opcodes.ILOAD, frame.depthSlot)
            pushInt(mv, StackVm.MAX_CALL_DEPTH)
            mv.visitJumpInsn(Opcodes.IF_ICMPLE, ok)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "dev/aetherx86/pyrite/vm/VmSupport",
                "callDepthExceeded",
                "()Ldev/aetherx86/pyrite/vm/VmTrap;",
                false,
            )
            mv.visitInsn(Opcodes.ATHROW)
            mv.visitLabel(ok)
        }
        // Locals start at zero, and the JVM requires them to be assigned. Arguments already are.
        val used = BooleanArray(256)
        var scan = 0
        while (scan < code.size) {
            val op = code[scan]
            if ((op == Op.LOAD || op == Op.STORE) && program.ownerOf(scan) == frame.owner) {
                used[code[scan + 1].toInt() and 0xFF] = true
            }
            scan += Op.length(op)
        }
        for (local in 0 until 256) {
            if (used[local] && (frame.isMain || local >= frame.argc)) {
                mv.visitInsn(Opcodes.ICONST_0)
                mv.visitVarInsn(Opcodes.ISTORE, frame.slotOf(local))
            }
        }

        val labels = HashMap<Int, Label>()
        val labelAt = { offset: Int -> labels.getOrPut(offset) { Label() } }
        // A function's body need not follow its ENTER in the code, so jump to where it starts.
        val start = if (frame.isMain) 0 else frame.owner + Op.length(Op.ENTER)
        var first = 0
        while (first < code.size && !(program.ownerOf(first) == frame.owner && code[first] != Op.ENTER)) {
            first += Op.length(code[first])
        }
        if (first != start) mv.visitJumpInsn(Opcodes.GOTO, labelAt(start))

        var pc = 0
        while (pc < code.size) {
            val op = code[pc]
            val length = Op.length(op)
            // Unreachable code and other frames' code are not part of this method.
            if (program.ownerOf(pc) != frame.owner || op == Op.ENTER) {
                pc += length
                continue
            }
            mv.visitLabel(labelAt(pc))
            when (op) {
                Op.CONST16, Op.CONST32 -> pushInt(mv, Verifier.readConst(code, pc))
                Op.ADD -> mv.visitInsn(Opcodes.IADD)
                Op.SUB -> mv.visitInsn(Opcodes.ISUB)
                Op.MUL -> mv.visitInsn(Opcodes.IMUL)
                Op.DIV -> emitDivision(mv, Opcodes.IDIV)
                Op.REM -> emitDivision(mv, Opcodes.IREM)
                Op.AND -> mv.visitInsn(Opcodes.IAND)
                Op.OR -> mv.visitInsn(Opcodes.IOR)
                Op.XOR -> mv.visitInsn(Opcodes.IXOR)
                Op.SHL -> mv.visitInsn(Opcodes.ISHL)
                Op.SHR -> mv.visitInsn(Opcodes.ISHR)
                Op.NEG -> mv.visitInsn(Opcodes.INEG)
                Op.LT -> emitCompare(mv, Opcodes.IF_ICMPLT)
                Op.EQ -> emitCompare(mv, Opcodes.IF_ICMPEQ)
                Op.DUP -> mv.visitInsn(Opcodes.DUP)
                Op.DROP -> mv.visitInsn(Opcodes.POP)
                Op.SWAP -> mv.visitInsn(Opcodes.SWAP)
                Op.LOAD -> mv.visitVarInsn(Opcodes.ILOAD, frame.slotOf(code[pc + 1].toInt() and 0xFF))
                Op.STORE -> mv.visitVarInsn(Opcodes.ISTORE, frame.slotOf(code[pc + 1].toInt() and 0xFF))
                Op.JMP -> mv.visitJumpInsn(Opcodes.GOTO, labelAt(Verifier.readU16(code, pc + 1)))
                Op.JZ -> mv.visitJumpInsn(Opcodes.IFEQ, labelAt(Verifier.readU16(code, pc + 1)))
                Op.JNZ -> mv.visitJumpInsn(Opcodes.IFNE, labelAt(Verifier.readU16(code, pc + 1)))
                Op.CALL -> {
                    val target = Verifier.readU16(code, pc + 1)
                    // The arguments are already on the JVM stack, in parameter order.
                    if (frame.isMain) {
                        mv.visitInsn(Opcodes.ICONST_1)
                    } else {
                        mv.visitVarInsn(Opcodes.ILOAD, frame.depthSlot)
                        mv.visitInsn(Opcodes.ICONST_1)
                        mv.visitInsn(Opcodes.IADD)
                    }
                    mv.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        CLASS_NAME,
                        methodName(target),
                        descriptor(code[target + 1].toInt() and 0xFF),
                        false,
                    )
                }
                Op.HALT, Op.RET -> mv.visitInsn(Opcodes.IRETURN)
            }
            pc += length
        }
    }

    private fun pushInt(mv: MethodVisitor, value: Int) {
        when (value) {
            in -1..5 -> mv.visitInsn(Opcodes.ICONST_0 + value)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(Opcodes.BIPUSH, value)
            in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(Opcodes.SIPUSH, value)
            else -> mv.visitLdcInsn(value)
        }
    }

    /** Pushes 1 if the comparison holds and 0 otherwise. */
    private fun emitCompare(mv: MethodVisitor, jump: Int) {
        val isTrue = Label()
        val done = Label()
        mv.visitJumpInsn(jump, isTrue)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitJumpInsn(Opcodes.GOTO, done)
        mv.visitLabel(isTrue)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitLabel(done)
    }

    private fun emitDivision(mv: MethodVisitor, op: Int) {
        // The JVM throws ArithmeticException here, but VmTrap keeps the backends identical.
        val ok = Label()
        mv.visitInsn(Opcodes.DUP)
        mv.visitJumpInsn(Opcodes.IFNE, ok)
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "dev/aetherx86/pyrite/vm/VmSupport",
            "divideByZero",
            "()Ldev/aetherx86/pyrite/vm/VmTrap;",
            false,
        )
        mv.visitInsn(Opcodes.ATHROW)
        mv.visitLabel(ok)
        mv.visitInsn(op)
    }
}

/** Helpers called from generated code. */
object VmSupport {
    @JvmStatic
    fun divideByZero(): VmTrap = VmTrap(VmTrap.Kind.DIVIDE_BY_ZERO)

    @JvmStatic
    fun callDepthExceeded(): VmTrap = VmTrap(VmTrap.Kind.CALL_DEPTH_EXCEEDED)
}
