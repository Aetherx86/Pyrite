package dev.aetherx86.pyrite

import dev.aetherx86.pyrite.bf.BfIo
import dev.aetherx86.pyrite.bf.BfJit
import dev.aetherx86.pyrite.bf.BfProgram
import dev.aetherx86.pyrite.bf.BfSyntaxError
import dev.aetherx86.pyrite.bf.BfTooLarge
import dev.aetherx86.pyrite.bf.DEFAULT_TAPE_SIZE
import dev.aetherx86.pyrite.bf.FlatInterpreter
import dev.aetherx86.pyrite.bf.Optimizer
import dev.aetherx86.pyrite.bf.OutOfFuel
import dev.aetherx86.pyrite.bf.Parser
import dev.aetherx86.pyrite.bf.TapeOutOfBounds
import dev.aetherx86.pyrite.vm.Assembler
import dev.aetherx86.pyrite.vm.AssemblyError
import dev.aetherx86.pyrite.vm.JitUnavailable
import dev.aetherx86.pyrite.vm.StackVm
import dev.aetherx86.pyrite.vm.Verifier
import dev.aetherx86.pyrite.vm.VerifyError
import dev.aetherx86.pyrite.vm.VmJit
import dev.aetherx86.pyrite.vm.VmTrap
import java.io.File
import kotlin.system.exitProcess

private val USAGE = """
    usage: pyrite <command> <file> [options]

      bf <file>    run a Brainfuck program
          --interpret      use the interpreter instead of the JIT
          --fuel N         stop after N loop iterations
          --tape N         tape size in cells (default $DEFAULT_TAPE_SIZE)
      vm <file>    assemble, verify and run a stack VM program
          --jit            compile to JVM bytecode instead of interpreting
          --fuel N         stop after N instructions (interpreter only)
""".trimIndent()

private fun runBf(args: List<String>) {
    val path = args.first()
    val options = args.drop(1)
    val fuel = valueOf(options, "--fuel")?.toLong()
    val tapeSize = valueOf(options, "--tape")?.toInt() ?: DEFAULT_TAPE_SIZE
    val ir = Optimizer.optimize(Parser.parse(File(path).readText()))
    val program: BfProgram = if ("--interpret" in options) {
        FlatInterpreter(ir)
    } else {
        try {
            BfJit.compile(ir, metered = fuel != null)
        } catch (e: BfTooLarge) {
            System.err.println("warning: falling back to the interpreter: ${e.message}")
            FlatInterpreter(ir)
        }
    }
    val io = BfIo(System.`in`, System.out, fuel = fuel ?: Long.MAX_VALUE)
    program.run(ByteArray(tapeSize), io)
}

private fun runVm(args: List<String>) {
    val path = args.first()
    val options = args.drop(1)
    val program = Verifier.verify(Assembler.assemble(File(path).readText()))
    val result = if ("--jit" in options) {
        VmJit.compile(program).run()
    } else {
        StackVm(program).run(valueOf(options, "--fuel")?.toLong() ?: Long.MAX_VALUE)
    }
    println(result)
}

private fun valueOf(options: List<String>, name: String): String? {
    val i = options.indexOf(name)
    return if (i >= 0) options.getOrNull(i + 1) ?: throw IllegalArgumentException("$name needs a value") else null
}

fun main(args: Array<String>) {
    if (args.size < 2) {
        println(USAGE)
        exitProcess(if (args.isEmpty()) 0 else 2)
    }
    try {
        when (args[0]) {
            "bf" -> runBf(args.drop(1))
            "vm" -> runVm(args.drop(1))
            else -> {
                println(USAGE)
                exitProcess(2)
            }
        }
    } catch (e: Exception) {
        when (e) {
            is BfSyntaxError, is AssemblyError, is VerifyError, is JitUnavailable, is VmTrap,
            is TapeOutOfBounds, is OutOfFuel, is IllegalArgumentException, is java.io.IOException,
            -> {
                System.err.println("error: ${e.message}")
                exitProcess(1)
            }
            else -> throw e
        }
    }
}
