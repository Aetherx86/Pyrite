# Pyrite

Two small languages compiled to JVM bytecode at run time, written in Kotlin on ASM. HotSpot's C2
compiler then turns the generated code into machine code.

- A stack bytecode VM with a verifier, an interpreter and a JIT
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

That last property is what makes the JIT simple and sound. The VM's stack maps directly onto the JVM's
operand stack, so the generated code holds no stack array at all, and C2 sees plain integer locals.

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
- Programs well past the JVM's method limit have to compile and run correctly.
- Known bugs were injected on purpose, such as a multiply loop that touches its targets when it should
  not run, a verifier that accepts inconsistent stacks and a pending pointer move lost across a loop.
  Each made the suite fail.

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

- **Numeric domain**: The stack VM and JVM bytecode JIT operate on 64-bit signed integers (`Long`) and bounded linear memory; floating-point instructions and garbage-collected heap objects are outside the instruction set.
- **JVM method size cap**: Very large programs are automatically split across helper methods to stay within the 64 KB JVM bytecode method limit, which introduces a small call boundary between segments.
