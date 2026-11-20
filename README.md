# Pyrite

Two small languages compiled to JVM bytecode at run time, written in Kotlin on ASM. HotSpot's C2
compiler then turns the generated code into machine code.

- A stack bytecode VM with functions, a verifier, an optimizer, an interpreter and a JIT
- A Brainfuck compiler with a peephole optimizer, an interpreter and a JIT

Pyrite is fool's gold, which is fitting for a JIT: it is not really gold, but it is surprisingly hard
to tell the difference from native code once C2 has finished with it.

## Stack VM

Instructions live in one flat `ByteArray`, so the dispatch loop allocates nothing. A test measures
this: the interpreter's hot loop stays under 4 KiB of allocation across a long run.

```text
    push 100
    store 0
top:
    load 0
    jz done
    load 1
    load 0
    add
    store 1
    load 0
    push 1
    sub
    store 0
    jmp top
done:
    load 1
    halt
```

Bytecode cannot run until the verifier accepts it. In the style of the JVM's own verifier, it proves
that verified code:

- never underflows or overflows the stack
- never jumps into the middle of an instruction
- never runs off the end of the program
- reaches each instruction with one consistent stack depth
- enters a function only through `call`, never jumps from one function into another, and returns
  from a function with exactly one value on its stack

The depth property is what makes the JIT simple and sound. The VM's stack maps directly onto the JVM's
operand stack, so the generated code holds no stack array at all, and C2 sees plain integer locals.

## Functions, optimizer and tooling (0.2.0)

### Functions

A function is a label on an `enter` instruction, whose operand is its argument count. `call`
pops the arguments, which arrive in the callee's locals 0, 1 and so on, and pushes the single
value the callee hands to `ret`. Each call gets a fresh set of locals, starting at zero.

```text
    push 27
    call fib
    halt
fib:
    enter 1
    load 0
    push 2
    lt
    jz recurse
    load 0
    ret
recurse:
    load 0
    push 1
    sub
    call fib
    load 0
    push 2
    sub
    call fib
    add
    ret
```

The verifier analyzes each function on its own, starting from an empty stack, and assigns every
instruction to exactly one owner: the main program or one function. The JIT relies on that. Each
function becomes its own static JVM method, with the VM arguments as JVM parameters, so a call
is a single `invokestatic` that C2 can inline. The interpreter keeps every frame in the same two
arrays, so once they have grown to the depth a program needs, calls allocate nothing.

Calls nest at most `StackVm.MAX_CALL_DEPTH` (512) deep. One more traps with
`CALL_DEPTH_EXCEEDED` on both backends, so unbounded recursion stops cleanly.

### Optimizer

`VmOptimizer.optimize` rewrites verified bytecode into smaller verified bytecode, repeating until
nothing changes:

| Before | After |
| --- | --- |
| unreachable code, uncalled functions | removed |
| `push 6; push 7; mul` | `push 42` |
| `push 0; jz L` / `push 1; jz L` | `jmp L` / removed |
| `push 0; add`, `push 1; mul`, `push -1; and`, `dup; drop`, `swap; swap`, `neg; neg` | removed |
| a jump to a `jmp` | a jump to its final target |
| `jmp` to the next instruction / `jz` to the next instruction | removed / `drop` |

Every round is verified again. A division by a constant zero is never folded, so it still traps,
and a rewrite never spans a jump target. Results and traps are unchanged. Fewer instructions run,
so a fuel budget goes further.

### Command line

Each of these was run on the programs in `examples/`.

```sh
$ pyrite vm examples/fib.asm --jit
196418

$ pyrite verify examples/fib.asm --json
{"ok":true,"codeBytes":41,"instructions":20,"reachableInstructions":20,"maxStack":3,"functions":[{"entry":7,"argc":1,"locals":1,"maxStack":3}]}

$ pyrite vm examples/fold.asm --optimize --json
{"ok":true,"result":903,"backend":"interpreter","codeBytes":36,"elapsedNanos":538542,"executed":470,"optimization":{"bytesBefore":57,"bytesAfter":36,"instructionsBefore":27,"instructionsAfter":17,"rounds":2}}

$ pyrite disasm examples/fold.asm --optimize
; optimized: 57 -> 36 bytes, 27 -> 17 instructions
    push 42          ; 0000  depth 0
    store 0          ; 0003  depth 1
    ...
L000a:
    load 0           ; 000a  depth 0
    jz L0021         ; 000c  depth 1
    ...

$ pyrite bench examples/fib.asm --runs 5
interpreter  result 196418  min 29.960 ms  median 31.632 ms  mean 35.247 ms  (6356209 instructions)
jit          result 196418  min 0.432 ms  median 0.435 ms  mean 0.437 ms  (compile 12.117 ms)
speedup      72.7x (median)
```

- `disasm` labels every jump target and notes each instruction's offset and the stack depth the
  verifier proved for it. Its output assembles back to the same bytes, as long as nothing is
  unreachable. Unreachable instructions are printed as comments.
- `bench` runs each backend once to warm up, then times `--runs` more runs, and exits with status
  1 if the backends disagree on the result or the trap. If the interpreter runs out of `--fuel`,
  the JIT is skipped, because the program may never stop.
- With `--json`, `vm`, `verify` and `bench` print one JSON object on standard output, including
  for assembly errors, verification errors and traps, such as
  `{"ok":false,"error":"trap","trap":"DIVIDE_BY_ZERO"}`. Errors exit with status 1, and a
  malformed command line, including an unknown option, exits with status 2.

## Brainfuck compiler

```text
source -> parser -> peephole optimizer -> threaded interpreter
                                       -> JVM bytecode in a hidden class
```

The optimizer folds runs of operations, removes pointer movement by giving each operation an offset,
and recognizes common loops.

| Source | Becomes |
| --- | --- |
| `+++++` | `Add(0, 5)` |
| `>>+<<` | `Add(2, 1)`, with no pointer movement at all |
| `[-]` or `[+]` | `Set(0, 0)` |
| `[-]+++` | `Set(0, 3)` |
| `[->+>++<<]` | `MulLoop`, one step rather than one iteration per unit |
| `[>]` | `Scan(1)` |

Each pass preserves the order of cell accesses, so a program that runs off the tape produces the same
output before failing whether or not it was optimized.

### Beyond the textbook version

A one-method translation to JVM bytecode falls over on large programs, for two separate reasons. A JVM
method cannot exceed 64 KiB, so a big program fails to load. And by default HotSpot refuses to compile
any method over 8000 bytes, so a program under the limit can still run interpreted forever, with no
warning.

This compiler keeps every method near a 6000-byte budget. It outlines big loops into their own methods
and splits long runs into a tree of helpers. Programs too large even for that, because a class holds
at most 65535 constants, get a clear `BfTooLarge` error, and the command line falls back to the
interpreter.

### Untrusted programs

- Memory safety comes from the JVM, since every cell access is bounds-checked.
- Compile with `metered = true`, and each loop iteration charges fuel from a budget you set, so an
  infinite loop stops with `OutOfFuel`.
- Parsing rejects nesting over 1000 levels, which bounds recursion in every later pass.

There is no limit on allocation other than the tape you supply. Set a tape size and a fuel budget for
programs you do not trust, and run them on a thread you control.

## Usage

```sh
./gradlew installDist
build/install/pyrite/bin/pyrite bf program.b
build/install/pyrite/bin/pyrite bf program.b --interpret
build/install/pyrite/bin/pyrite bf program.b --fuel 1000000 --tape 65536
build/install/pyrite/bin/pyrite vm program.asm --jit
build/install/pyrite/bin/pyrite vm program.asm --optimize --json
build/install/pyrite/bin/pyrite verify program.asm --json
build/install/pyrite/bin/pyrite disasm program.asm --optimize
build/install/pyrite/bin/pyrite bench program.asm --runs 10 --json
build/install/pyrite/bin/pyrite --version
```

To use the library, it is published to GitHub Packages as `dev.aetherx86.pyrite:pyrite`.

```kotlin
val ir = Optimizer.optimize(Parser.parse(source))
val program = BfJit.compile(ir)
program.run(ByteArray(30_000), BfIo(System.`in`, System.out))
```

## How it is tested

- A deliberately simple character-at-a-time interpreter defines correct behavior. Thousands of random
  programs must produce the same output, errors, final pointer and tape on the optimized interpreter
  and on the JIT, with and without optimization. The test fails if the generator stops producing both
  programs that complete and programs that fail.
- The VM's interpreter and JIT are compared on random verified programs, including traps.
- Random programs with up to four functions, calls between them and many patterns the optimizer
  rewrites must give the same result or trap on both backends, before and after optimization.
  The optimized code must be no larger, and optimizing it again must change nothing.
- The disassembler's output for random programs must assemble back to the same bytes, or, where
  the program has dead code, to a program with the same behavior.
- The command line is tested in-process, including every JSON shape and exit status.
- Programs well past the JVM's method limit have to compile and run correctly.
- Known bugs were injected on purpose, such as a multiply loop that touches its targets when it should
  not run, a verifier that accepts inconsistent stacks and a pending pointer move lost across a loop.
  For 0.2.0 the same was done to the new code: an optimizer that treats `push 0; mul` as an
  identity, loses the label of a removed instruction or rewrites across a jump target, an
  interpreter that forgets the caller's locals or does not clear the callee's, a JIT that maps
  arguments to the wrong JVM slots, and a verifier that allows `ret` with extra values. Each made
  the suite fail.

## Measured

Single runs on an Apple Silicon laptop, including JVM startup.

| Program | Interpreter | JIT |
| --- | --- | --- |
| VM: a loop summing 300 million values | 8.39 s | 0.21 s |
| Brainfuck: 100 million iterations of a nested loop | 0.63 s | 0.26 s |

The Brainfuck interpreter is already fast, because it runs on the optimized IR, and C2 compiles the
interpreter itself. The VM's interpreter has more work to do per instruction.

## Versioning

A bug or security fix increments `0.0.X`. New functionality or new platform support increments
`0.X.0`. See [CHANGELOG.md](CHANGELOG.md).

## License

MIT. See [LICENSE](LICENSE).

## Limitations

- **Numeric domain**: The stack VM operates on 32-bit signed integers (`Int`) that wrap on
  overflow, held on the operand stack and in up to 256 locals per frame. It has no heap, arrays or
  floating-point instructions.
- **JVM method size cap**: The Brainfuck compiler splits very large programs across helper
  methods to stay within the JVM's method limits. The VM JIT does not split the main program or a
  function. One that is too large for a JVM method fails with `JitUnavailable`, and runs on the
  interpreter instead. Splitting a large program into functions also keeps each method small.
- **Call depth**: The JIT's calls nest on the JVM thread stack. If a thread's stack is too small
  to reach the 512-call limit, the overflow is still reported as `CALL_DEPTH_EXCEEDED`, but
  earlier than on the interpreter.
