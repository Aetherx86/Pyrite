; Constant work the optimizer folds away, around a real loop.
    push 6
    push 7
    mul
    store 0          ; 42
    push 0
    store 1
top:
    load 0
    jz done
    load 1
    load 0
    push 0
    add
    add
    push 1
    mul
    store 1
    load 0
    push 1
    sub
    store 0
    push 1
    jnz top          ; always taken
done:
    load 1
    halt
unused:
    enter 0
    push 9
    ret
