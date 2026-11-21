# Changelog

Versions follow this rule: a bug or security fix increments `0.0.X`, and new functionality
or new platform support increments `0.X.0`.

## [0.2.1] - 2026-11-21

- The CLI and disassembler tests compare output with normalized line endings, so they pass on
  Windows, where `println` writes CRLF. No change to the library or the CLI.

## [0.2.0] - 2026-11-20

- Functions in the stack VM: `call`, `enter` and `ret`. The verifier proves each function
  separately, checks its argument count at every call site, gives every instruction exactly one
  owning function, and requires `ret` to leave exactly one value. The interpreter keeps frames in
  reusable arrays, and the JIT compiles each function into its own static JVM method.
- A call depth limit of 512 on both backends, which traps with `CALL_DEPTH_EXCEEDED`.
- `VmOptimizer`, which removes unreachable code and uncalled functions, folds constants and
  branches on constants, removes identities and cancelling pairs, and threads jumps. Its output is
  verified again after each round, and is a fixed point.
- `Disassembler`, which prints verified bytecode as labelled assembler text annotated with offsets
  and proven stack depths, and reassembles to the same bytes when nothing is unreachable.
- The verifier's analysis is public: `stackDepthAt`, `isReachable`, `ownerOf`, `functions`,
  `instructionCount` and `reachableInstructionCount`.
- New commands `verify`, `disasm` and `bench`, the options `--optimize` and `--json` for `vm`, and
  `--version`. `bench` checks that the interpreter and the JIT agree.
- The command line rejects unknown options and malformed numbers with exit status 2. `vm --jit`
  with `--fuel` is now an error, since the JIT does not meter fuel.
- Differential tests for calls and for the optimizer on random programs, round-trip tests for the
  disassembler, and in-process tests of the command line.

## 0.1.0

First release.

- A stack bytecode VM with a verifier, an assembler and a zero-allocation interpreter.
- A JIT that translates verified VM bytecode directly to JVM bytecode.
- A Brainfuck compiler with a peephole optimizer, a threaded interpreter and a JVM JIT.
- Automatic splitting of large programs around the JVM's method size limits.
- Optional fuel metering, so that infinite loops in untrusted programs can be stopped.
