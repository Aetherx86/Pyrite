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
import dev.aetherx86.pyrite.vm.Disassembler
import dev.aetherx86.pyrite.vm.JitUnavailable
import dev.aetherx86.pyrite.vm.OptimizationReport
import dev.aetherx86.pyrite.vm.StackVm
import dev.aetherx86.pyrite.vm.VerifiedProgram
import dev.aetherx86.pyrite.vm.Verifier
import dev.aetherx86.pyrite.vm.VerifyError
import dev.aetherx86.pyrite.vm.VmJit
import dev.aetherx86.pyrite.vm.VmOptimizer
import dev.aetherx86.pyrite.vm.VmTrap
import java.io.File
import java.io.InputStream
import java.io.PrintStream
import java.util.Locale
import kotlin.system.exitProcess

const val VERSION = "0.2.1"

private val USAGE = """
    usage: pyrite <command> <file> [options]

      bf <file>       run a Brainfuck program
          --interpret      use the interpreter instead of the JIT
          --fuel N         stop after N loop iterations
          --tape N         tape size in cells (default $DEFAULT_TAPE_SIZE)
      vm <file>       assemble, verify and run a stack VM program
          --jit            compile to JVM bytecode instead of interpreting
          --fuel N         stop after N instructions (interpreter only)
          --optimize       run the bytecode optimizer first
          --json           print the result as JSON
      verify <file>   assemble and verify, then report what the verifier proved
          --json           print the report as JSON
      disasm <file>   print verified bytecode as annotated assembler text
          --optimize       disassemble the optimized program
      bench <file>    time the interpreter and the JIT, and check that they agree
          --runs N         timed runs per backend after one warm-up (default 5)
          --fuel N         instruction budget for the interpreter
          --optimize       run the bytecode optimizer first
          --json           print the measurements as JSON
      --version       print the version
""".trimIndent()

/** Thrown for a malformed command line. Exits with status 2. */
internal class UsageError(message: String) : Exception(message)

/** The command line, with its streams injected so that it can be tested. */
internal class Cli(
    private val stdin: InputStream,
    private val out: PrintStream,
    private val err: PrintStream,
) {
    private class Options(args: List<String>, flags: Set<String>, valued: Set<String>) {
        val present = HashSet<String>()
        val values = HashMap<String, String>()

        init {
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                when (arg) {
                    in flags -> present.add(arg)
                    in valued -> {
                        values[arg] = args.getOrNull(i + 1) ?: throw UsageError("$arg needs a value")
                        i++
                    }
                    else -> throw UsageError("unknown option '$arg'")
                }
                i++
            }
        }

        operator fun contains(flag: String) = flag in present

        fun long(name: String, range: LongRange): Long? = values[name]?.let {
            it.toLongOrNull()?.takeIf { v -> v in range }
                ?: throw UsageError("$name needs a number in ${range.first}..${range.last}")
        }
    }

    fun run(args: Array<String>): Int {
        if (args.size == 1 && args[0] == "--version") {
            out.println("pyrite $VERSION")
            return 0
        }
        if (args.size < 2) {
            out.println(USAGE)
            return if (args.isEmpty()) 0 else 2
        }
        val command = args[0]
        val path = args[1]
        val rest = args.drop(2)
        val json = "--json" in rest
        return try {
            when (command) {
                "bf" -> runBf(path, Options(rest, setOf("--interpret"), setOf("--fuel", "--tape")))
                "vm" -> runVm(path, Options(rest, setOf("--jit", "--optimize", "--json"), setOf("--fuel")))
                "verify" -> verify(path, Options(rest, setOf("--json"), emptySet()))
                "disasm" -> disasm(path, Options(rest, setOf("--optimize"), emptySet()))
                "bench" -> bench(path, Options(rest, setOf("--optimize", "--json"), setOf("--runs", "--fuel")))
                else -> {
                    err.println("error: unknown command '$command'")
                    out.println(USAGE)
                    2
                }
            }
        } catch (e: UsageError) {
            err.println("error: ${e.message}")
            2
        } catch (e: Exception) {
            val known = e is BfSyntaxError || e is AssemblyError || e is VerifyError ||
                e is JitUnavailable || e is VmTrap || e is TapeOutOfBounds || e is OutOfFuel ||
                e is java.io.IOException
            if (!known) throw e
            if (json && command != "bf") out.println(Json.write(errorJson(e))) else err.println("error: ${e.message}")
            1
        }
    }

    private fun errorJson(e: Exception): Map<String, Any?> = when (e) {
        is AssemblyError -> mapOf("ok" to false, "error" to "assembly", "line" to e.line, "message" to e.message)
        is VerifyError -> mapOf("ok" to false, "error" to "verify", "offset" to e.offset, "message" to e.message)
        is VmTrap -> mapOf("ok" to false, "error" to "trap", "trap" to e.kind.name)
        is JitUnavailable -> mapOf("ok" to false, "error" to "jit", "message" to e.message)
        else -> mapOf("ok" to false, "error" to "io", "message" to e.message)
    }

    private fun runBf(path: String, options: Options): Int {
        val fuel = options.long("--fuel", 0..Long.MAX_VALUE)
        val tapeSize = options.long("--tape", 1..Int.MAX_VALUE.toLong())?.toInt() ?: DEFAULT_TAPE_SIZE
        val ir = Optimizer.optimize(Parser.parse(File(path).readText()))
        val program: BfProgram = if ("--interpret" in options) {
            FlatInterpreter(ir)
        } else {
            try {
                BfJit.compile(ir, metered = fuel != null)
            } catch (e: BfTooLarge) {
                err.println("warning: falling back to the interpreter: ${e.message}")
                FlatInterpreter(ir)
            }
        }
        val io = BfIo(stdin, out, fuel = fuel ?: Long.MAX_VALUE)
        program.run(ByteArray(tapeSize), io)
        return 0
    }

    /** Assembles and verifies, then optimizes if asked. */
    private fun load(path: String, optimize: Boolean): Pair<VerifiedProgram, OptimizationReport?> {
        val program = Verifier.verify(Assembler.assemble(File(path).readText()))
        if (!optimize) return program to null
        val optimized = VmOptimizer.optimize(program)
        return optimized.program to optimized.report
    }

    private fun reportJson(report: OptimizationReport) = mapOf(
        "bytesBefore" to report.bytesBefore,
        "bytesAfter" to report.bytesAfter,
        "instructionsBefore" to report.instructionsBefore,
        "instructionsAfter" to report.instructionsAfter,
        "rounds" to report.rounds,
    )

    private fun runVm(path: String, options: Options): Int {
        val fuel = options.long("--fuel", 0..Long.MAX_VALUE) ?: Long.MAX_VALUE
        val jit = "--jit" in options
        if (jit && options.values.containsKey("--fuel")) throw UsageError("--fuel only applies to the interpreter")
        val (program, report) = load(path, "--optimize" in options)
        val started = System.nanoTime()
        var executed: Long? = null
        val result = if (jit) {
            VmJit.compile(program).run()
        } else {
            val vm = StackVm(program)
            try {
                vm.run(fuel)
            } finally {
                executed = vm.executed
            }
        }
        val elapsed = System.nanoTime() - started
        if ("--json" in options) {
            val fields = linkedMapOf<String, Any?>(
                "ok" to true,
                "result" to result,
                "backend" to if (jit) "jit" else "interpreter",
                "codeBytes" to program.code.size,
                "elapsedNanos" to elapsed,
            )
            if (executed != null) fields["executed"] = executed
            if (report != null) fields["optimization"] = reportJson(report)
            out.println(Json.write(fields))
        } else {
            out.println(result)
        }
        return 0
    }

    private fun verify(path: String, options: Options): Int {
        val program = Verifier.verify(Assembler.assemble(File(path).readText()))
        if ("--json" in options) {
            out.println(
                Json.write(
                    mapOf(
                        "ok" to true,
                        "codeBytes" to program.code.size,
                        "instructions" to program.instructionCount,
                        "reachableInstructions" to program.reachableInstructionCount,
                        "maxStack" to program.maxStack,
                        "functions" to program.functions.map {
                            mapOf(
                                "entry" to it.entry,
                                "argc" to it.argc,
                                "locals" to it.localCount,
                                "maxStack" to it.maxStack,
                            )
                        },
                    ),
                ),
            )
        } else {
            out.println("ok: ${program.code.size} bytes, ${program.instructionCount} instructions")
            out.println("reachable instructions: ${program.reachableInstructionCount}")
            out.println("max stack depth: ${program.maxStack}")
            for (f in program.functions) {
                out.println(
                    "function at ${f.entry}: ${f.argc} argument(s), ${f.localCount} local(s), " +
                        "max stack ${f.maxStack}",
                )
            }
        }
        return 0
    }

    private fun disasm(path: String, options: Options): Int {
        val (program, report) = load(path, "--optimize" in options)
        if (report != null) {
            out.println(
                "; optimized: ${report.bytesBefore} -> ${report.bytesAfter} bytes, " +
                    "${report.instructionsBefore} -> ${report.instructionsAfter} instructions",
            )
        }
        out.print(Disassembler.disassemble(program))
        return 0
    }

    private class Timing(val runs: LongArray) {
        val min get() = runs.min()
        val median get() = runs.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        val mean get() = runs.sum() / runs.size
    }

    /** One untimed warm-up run, then [runs] timed ones. Returns the result or the trap. */
    private fun measure(runs: Int, body: () -> Int): Pair<Result<Int>, Timing?> {
        val first = runCatching(body)
        if (first.exceptionOrNull() is VmTrap) return first to null
        first.getOrThrow()
        val times = LongArray(runs)
        for (i in 0 until runs) {
            val t0 = System.nanoTime()
            body()
            times[i] = System.nanoTime() - t0
        }
        return first to Timing(times)
    }

    private fun bench(path: String, options: Options): Int {
        val runs = options.long("--runs", 1..1_000_000L)?.toInt() ?: 5
        val fuel = options.long("--fuel", 0..Long.MAX_VALUE) ?: Long.MAX_VALUE
        val (program, report) = load(path, "--optimize" in options)

        val vm = StackVm(program)
        val (interpreted, interpreterTiming) = measure(runs) { vm.run(fuel) }
        val executed = vm.executed
        val interpreterTrap = (interpreted.exceptionOrNull() as? VmTrap)?.kind

        // A program that ran out of fuel may not terminate at all, so the JIT is not tried.
        var jitUnavailable: String? = null
        var compileNanos = 0L
        var compiled: Result<Int>? = null
        var jitTiming: Timing? = null
        if (interpreterTrap == VmTrap.Kind.OUT_OF_FUEL) {
            jitUnavailable = "the interpreter ran out of fuel"
        } else {
            try {
                val t0 = System.nanoTime()
                val jit = VmJit.compile(program)
                compileNanos = System.nanoTime() - t0
                val measured = measure(runs) { jit.run() }
                compiled = measured.first
                jitTiming = measured.second
            } catch (e: JitUnavailable) {
                jitUnavailable = e.message
            }
        }
        val jitTrap = (compiled?.exceptionOrNull() as? VmTrap)?.kind
        val agree = compiled == null ||
            (interpreterTrap == jitTrap && interpreted.getOrNull() == compiled.getOrNull())

        fun backendJson(result: Result<Int>, timing: Timing?): MutableMap<String, Any?> {
            val m = linkedMapOf<String, Any?>()
            val trap = result.exceptionOrNull() as? VmTrap
            if (trap != null) m["trap"] = trap.kind.name else m["result"] = result.getOrNull()
            if (timing != null) {
                m["minNanos"] = timing.min
                m["medianNanos"] = timing.median
                m["meanNanos"] = timing.mean
            }
            return m
        }

        if ("--json" in options) {
            val fields = linkedMapOf<String, Any?>(
                "ok" to agree,
                "runs" to runs,
                "codeBytes" to program.code.size,
                "agree" to agree,
                "interpreter" to backendJson(interpreted, interpreterTiming).also { it["executed"] = executed },
                "jit" to if (compiled == null) {
                    mapOf("unavailable" to jitUnavailable)
                } else {
                    backendJson(compiled, jitTiming).also { it["compileNanos"] = compileNanos }
                },
            )
            if (report != null) fields["optimization"] = reportJson(report)
            out.println(Json.write(fields))
        } else {
            if (report != null) {
                out.println("optimized: ${report.bytesBefore} -> ${report.bytesAfter} bytes")
            }
            fun line(name: String, result: Result<Int>, timing: Timing?, extra: String) {
                val outcome = (result.exceptionOrNull() as? VmTrap)?.let { "trap ${it.kind}" }
                    ?: "result ${result.getOrNull()}"
                val times = timing?.let {
                    "  min %s  median %s  mean %s".format(ms(it.min), ms(it.median), ms(it.mean))
                } ?: ""
                out.println("%-12s %s%s%s".format(name, outcome, times, extra))
            }
            line("interpreter", interpreted, interpreterTiming, "  ($executed instructions)")
            if (compiled == null) {
                out.println("%-12s unavailable: %s".format("jit", jitUnavailable))
            } else {
                line("jit", compiled, jitTiming, "  (compile ${ms(compileNanos)})")
                if (interpreterTiming != null && jitTiming != null && jitTiming.median > 0) {
                    out.println(String.format(Locale.ROOT, "speedup      %.1fx (median)", interpreterTiming.median.toDouble() / jitTiming.median))
                }
            }
            if (!agree) out.println("MISMATCH: the backends disagree")
        }
        return if (agree) 0 else 1
    }

    private fun ms(nanos: Long) = String.format(Locale.ROOT, "%.3f ms", nanos / 1e6)
}

fun main(args: Array<String>) {
    val code = Cli(System.`in`, System.out, System.err).run(args)
    System.out.flush()
    exitProcess(code)
}
