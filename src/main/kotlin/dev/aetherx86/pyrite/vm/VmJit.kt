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
            emitBody(this, program)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()

        val bytes = try {
            cw.toByteArray()
        } catch (e: MethodTooLargeException) {
            throw JitUnavailable("the program is too large for one JVM method (${e.codeSize} bytes)")
        }
        val lookup = MethodHandles.lookup().defineHiddenClass(bytes, true)
        val constructor = lookup.findConstructor(lookup.lookupClass(), MethodType.methodType(Void.TYPE))
        return constructor.invoke() as VmProgram
    }

    private fun emitBody(mv: MethodVisitor, program: VerifiedProgram) {
        val code = program.code
        // Locals start at slot 1, after `this`, and the JVM requires them to be assigned.
        val used = BooleanArray(256)
        var scan = 0
        while (scan < code.size) {
            val op = code[scan]
            if (op == Op.LOAD || op == Op.STORE) used[code[scan + 1].toInt() and 0xFF] = true
            scan += Op.length(op)
        }
        for (slot in 0 until 256) {
            if (used[slot]) {
                mv.visitInsn(Opcodes.ICONST_0)
                mv.visitVarInsn(Opcodes.ISTORE, slot + 1)
            }
        }

        val labels = HashMap<Int, Label>()
        val labelAt = { offset: Int -> labels.getOrPut(offset) { Label() } }
        var pc = 0
        while (pc < code.size) {
            val op = code[pc]
            mv.visitLabel(labelAt(pc))
            when (op) {
                Op.CONST16 -> pushInt(mv, (code[pc + 1].toInt() shl 8) or (code[pc + 2].toInt() and 0xFF))
                Op.CONST32 -> pushInt(
                    mv,
                    (code[pc + 1].toInt() shl 24) or
                        ((code[pc + 2].toInt() and 0xFF) shl 16) or
                        ((code[pc + 3].toInt() and 0xFF) shl 8) or
                        (code[pc + 4].toInt() and 0xFF),
                )
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
                Op.LOAD -> mv.visitVarInsn(Opcodes.ILOAD, (code[pc + 1].toInt() and 0xFF) + 1)
                Op.STORE -> mv.visitVarInsn(Opcodes.ISTORE, (code[pc + 1].toInt() and 0xFF) + 1)
                Op.JMP -> mv.visitJumpInsn(Opcodes.GOTO, labelAt(Verifier.readU16(code, pc + 1)))
                Op.JZ -> mv.visitJumpInsn(Opcodes.IFEQ, labelAt(Verifier.readU16(code, pc + 1)))
                Op.JNZ -> mv.visitJumpInsn(Opcodes.IFNE, labelAt(Verifier.readU16(code, pc + 1)))
                Op.HALT -> mv.visitInsn(Opcodes.IRETURN)
            }
            pc += Op.length(op)
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
}
