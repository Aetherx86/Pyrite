; Recursive Fibonacci: fib(n) = n < 2 ? n : fib(n - 1) + fib(n - 2)
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
