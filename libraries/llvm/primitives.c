#ifndef EFFEKT_PRIMITIVES_C
#define EFFEKT_PRIMITIVES_C


// types

struct Pos {
    uint64_t tag; // type-local tag
    void *obj; // heap object
};

typedef int64_t Int;
typedef double Double;

static const struct Pos Unit = (struct Pos) { .tag = 0, .obj = NULL, };
static const struct Pos BooleanFalse = (struct Pos) { .tag = 0, .obj = NULL, };
static const struct Pos BooleanTrue = (struct Pos) { .tag = 1, .obj = NULL, };


// i/o

void c_println_TODO() {
    puts("TODO");
}

struct Pos c_println_Int(const Int n) {
    printf("%ld\n", n);
    return Unit;
}

struct Pos c_println_Boolean(const struct Pos p) {
    printf("%s\n", p.tag ? "true" : "false");
    return Unit;
}

struct Pos c_println_Double(const Double x) {
    printf("%g\n", x);
    return Unit;
}


// arithmetic

#define C_OP(T, OP_NAME, OP) \
    T c_ ## OP_NAME ## _ ## T ## _ ## T (const T x, const T y) { \
        return x OP y; }

// integer arithmetic
C_OP(Int, add, +)
C_OP(Int, sub, -)
C_OP(Int, mul, *)
C_OP(Int, div, /)
C_OP(Int, mod, %)

// floating-point arithmetic
C_OP(Double, add, +)
C_OP(Double, sub, -)
C_OP(Double, mul, *)
C_OP(Double, div, /)
// NOTE: requires linking against `-lm` and `#include <math.h>`
Double c_mod_Double_Double(Double x, Double y) { return fmod(x, y); }

#undef C_OP


// buffer

#define MALLOC_PANIC() (fprintf(stderr, "*** MALLOC PANIC\n"), fflush(stderr))

struct Pos c_buffer_heapify(const uint32_t len, const uint8_t *utf8) {
    uint8_t *buf = malloc(len * sizeof *buf);
    if (!buf)
        MALLOC_PANIC();
    for (uint32_t j = 0; j != len; ++j)
        buf[j] = utf8[j];
    return (struct Pos) {
        .tag = (((uint64_t) len) << 32) | ((uint64_t) len),
        .obj = buf,
    };
}

void c_buffer_println(const struct Pos pos) {
    const uint32_t len = pos.tag & 0xffffffff;
    const uint8_t *buf = (uint8_t *) pos.obj;

    //fprintf(/*stderr*/stdout, "c_buffer_println(tag=%ld, obj=%p): len=%d: %d,%d,%d", pos.tag, pos.obj, len, buf[0], buf[1], buf[2]);

    for (uint32_t j = 0; j != len; ++j)
        putc(buf[j], stdout);
}


#endif
