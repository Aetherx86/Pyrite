# Changelog

Versions follow this rule: a bug or security fix increments `0.0.X`, and new functionality
or new platform support increments `0.X.0`.

## 0.1.0

First release.

- A stack bytecode VM with a verifier, an assembler and a zero-allocation interpreter.
- A JIT that translates verified VM bytecode directly to JVM bytecode.
- A Brainfuck compiler with a peephole optimizer, a threaded interpreter and a JVM JIT.
- Automatic splitting of large programs around the JVM's method size limits.
- Optional fuel metering, so that infinite loops in untrusted programs can be stopped.
