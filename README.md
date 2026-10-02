# stacksafe

`@StackSafe` makes deep recursion on the JVM bounded by heap instead of thread stack size, with a bytecode transform and no source changes beyond the annotation.

## Why

A generated expression tree is the realistic source of unbounded depth. A query builder that emits a 500k-term `a + b + c + ...` produces a left-deep tree, and the evaluator recurses once per term. The usual answers (see [Alternatives](#alternatives)) are a bigger stack, which has to be sized up front, or rewriting the evaluator around an explicit work stack. Java has no tail calls and annotation processors cannot rewrite method bodies, so a bytecode transform is the natural home for doing it automatically.

```java
@StackSafe
static long eval(Expr e) {
    return switch (e) {
        case Lit l -> l.value();
        case Add a -> eval(a.left()) + eval(a.right());
        case Neg n -> -eval(n.operand());
    };
}
```

## Usage

```
java -javaagent:stacksafe.jar[=interval=1024][,backend=virtual|continuation] -cp app.jar ...
```

Build with `mvn package`; the agent jar (`target/stacksafe-*.jar`) bundles a shaded ASM and the `@StackSafe` annotation, so put it on the compile classpath too. Requires JDK 21+. The default backend uses public API only; see [Backends](#backends).

`interval` is the number of recursive calls between yields. It must be a power of two, and about `interval` frames (more with unannotated frames in between, or the thread-local mode's two frames per level) must fit in the stack the segment runs on. 1024 is comfortable at default stack sizes; methods with large frames, or a small stack, need less. The tests use 256 on a 1 MB thread.

## How it works

For each `@StackSafe` method `m(args)` the transform emits:

| Method | Role |
|---|---|
| `m$ss(args, int depth)` | The original body, cloned with a synthetic trailing `int`. Entry checks `(depth & (interval-1)) == interval-1` and calls `StackSafeRuntime.yieldNow()`. Calls to other `@StackSafe` methods (including mutual recursion) are redirected to their `$ss` twin, passing `depth + 1`. |
| `m$ss$thunk(args)` | Static adapter that calls the twin with depth 0 and boxes the result. |
| `m(args)` | Same signature, annotations and generics as before; body is `StackSafeRuntime.run(thunk)`. |

The depth travels as a parameter, so there is nothing to decrement on unwind and no `ThreadLocal`.

`run` executes the body on a virtual thread; `yieldNow` is `Thread.yield()`, which unmounts the virtual thread and copies its frames to the heap. Exceptions are captured inside the virtual thread and rethrown, with their original type, on the calling thread. A yield on a pinned virtual thread is a no-op, so the recursion simply continues on the stack until the next interval.

## Loom primer

The mechanism this project relies on comes from Project Loom, delivered in JDK 21 as virtual threads.

- **A virtual thread is a `Thread` whose stack lives on the heap while it is not running.** It is scheduled onto a small pool of platform *carrier* threads (a `ForkJoinPool` by default). Mounted, its frames sit on the carrier's stack and run as normal compiled code.
- **Unmounting copies the frames to the heap.** When a virtual thread blocks, parks or yields, the JVM *freezes* its frames into heap objects (stack chunks) and frees the carrier. When it is scheduled again, the frames are *thawed* back lazily: only the top few frames are copied to the carrier's stack, and a return barrier thaws more as the code returns into them. That laziness is why a deep recursion that yields every `interval` calls does not copy the whole stack on every yield.
- **`Thread.yield()` on a virtual thread is a cheap unmount.** It freezes the current frames and resubmits the thread to the scheduler. On a platform thread it is only a scheduling hint, so calling it from a transformed method outside `run` is harmless.
- **The heap bounds the depth, not the carrier's stack.** Only the frames since the last yield are on the carrier's stack at any time, which is why `interval` frames must fit there (see [Caveats](#caveats)). The spike ran 60M frames deep this way.
- **Pinning.** A virtual thread cannot unmount while it has a native frame on its stack, or a frame the VM cannot freeze. Before JDK 24 `synchronized` also pinned; JEP 491 removed that. When a yield is attempted while pinned, `Thread.yield()` simply returns and the thread keeps running on the carrier.
- **Underneath is `jdk.internal.vm.Continuation`,** the one-shot delimited continuation that virtual threads are built on. It is internal API and needs `--add-exports`, so the default backend uses virtual threads; the [`continuation` backend](#backends) drives it directly on the caller's thread and is somewhat faster.

References:

- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444) (final in JDK 21; [JEP 425](https://openjdk.org/jeps/425) was the first preview)
- [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491) (JDK 24)
- [Virtual Threads, Java SE 21 core libraries guide](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)
- [`Thread` Javadoc, Java SE 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Thread.html) (`ofVirtual`, `yield`)
- [Project Loom wiki](https://wiki.openjdk.org/display/loom)
- [State of Loom, part 1](https://cr.openjdk.org/~rpressler/loom/loom/sol1_part1.html) (Ron Pressler; the original design write-up, written before the final API)

## Backends

Where the body of a transformed method runs between yields, chosen once at startup with `backend=` on the agent (or `-Dstacksafe.backend=`):

| | `virtual` (default) | `continuation` |
|---|---|---|
| API | public (virtual threads, `Thread.yield()`) | internal `jdk.internal.vm.Continuation`; the agent exports the package to itself, or pass `--add-exports java.base/jdk.internal.vm=ALL-UNNAMED` |
| Body runs on | a new virtual thread; the caller waits | the caller's own thread |
| `Thread.currentThread()`, thread-locals, context class loader | differ from the caller's | the caller's |
| Locks held by the caller | not held by the body (a deadlock if the body takes one) | held; reentrancy works |
| Scheduler | the virtual-thread scheduler; a yield re-queues the thread | none; a yield returns to the driver loop on the same thread |
| Stack headroom per segment | a carrier thread's stack | what is left of the caller's own stack |
| Speed (10M-deep `Add`, parameter mode) | 370 ms | 270 ms |
| Stability | stable API | internal; can change between JDK releases |

The tests run the whole suite once per backend. Choose `continuation` when the recursion must see the caller's thread-locals or locks, or when the scheduler is a concern, and accept that it is unsupported API.

## Depth modes

```java
@StackSafe                              // Depth.PARAMETER (default)
@StackSafe(depth = Depth.THREAD_LOCAL)
```

| | `PARAMETER` | `THREAD_LOCAL` |
|---|---|---|
| Depth lives in | synthetic `int` parameter of a cloned `m$ss` | per-thread `int[]` in one shared `StackSafeRuntime.DEPTH` `ThreadLocal`, read directly from each method (a `static final`, so the JIT folds it) |
| Call sites | redirected to the callee's `$ss` twin | untouched |
| Overridable / interface methods | not redirected; entry point restarts the parameter depth | work |
| Mutual recursion across classes | works if callees are statically bound | works |
| Through unannotated frames | parameter depth restarts, but the crossing is counted on the shared counter | counter carries through |
| Cost | the baseline | measured 2-4x slower on the 10M-deep `Add` chain below |

`THREAD_LOCAL` rewrites `m` in place. The original body moves to a private `m$ss$body`, and `m` becomes:

```java
int[] d = StackSafeRuntime.DEPTH.get();
if (d == null) return run(() -> m$ss$body(args));   // outermost call starts a segment
enter(d, mask);                                      // ++d[0]; yield every `interval` calls
try { return m$ss$body(args); } finally { leave(d); }
```

It uses two frames per level instead of one, so the same `interval` needs about twice the stack headroom. Both modes can be mixed in one program.

## What the transform emits

Shown as the equivalent Java source; the `invokedynamic` is written as a lambda and the synthetic flags as comments.

### `Depth.PARAMETER`

```java
@StackSafe
static long eval(Expr e) {
    return switch (e) {
        case Lit l -> l.value();
        case Add a -> eval(a.left()) + eval(a.right());
        case Neg n -> -eval(n.operand());
    };
}
```

becomes three methods:

```java
// 1. Entry point: same name, signature, annotations and generics, so callers and reflection see no
//    difference. The lambda is an invokedynamic (LambdaMetafactory) capturing the arguments.
//    Reached from outside the recursion, and from any crossing through unannotated or overridable code.
static long eval(Expr e) {
    int[] d = StackSafeRuntime.DEPTH.get();            // one shared ThreadLocal; null outside a segment
    if (d == null)                                      // first entry: start a segment on a virtual thread
        return (Long) StackSafeRuntime.run(() -> eval$ss$thunk(e));
    StackSafeRuntime.enter(d, 1023);                    // already inside one: count the crossing, yield every interval
    try {
        return eval$ss(e, 0);                           // restart the parameter depth; invokespecial, never an override
    } finally {
        StackSafeRuntime.leave(d);
    }
}

// 2. Thunk (private static synthetic): starts the depth at 0 and boxes the result.
private static Object eval$ss$thunk(Expr e) {
    return Long.valueOf(eval$ss(e, 0));
}

// 3. Twin (synthetic, same access as the original): the original method node, renamed, with a
//    trailing int parameter and an entry check.
static long eval$ss(Expr e, int depth) {
    if ((depth & 1023) == 1023)             // interval - 1, baked in as a constant
        StackSafeRuntime.yieldNow();        // Thread.yield() on the virtual thread
    return switch (e) {
        case Lit l -> l.value();
        // calls to statically bound @StackSafe targets now call the twin with depth + 1
        case Add a -> eval$ss(a.left(), depth + 1) + eval$ss(a.right(), depth + 1);
        case Neg n -> -eval$ss(n.operand(), depth + 1);
    };
}
```

- The `depth` parameter takes the first slot after the original parameters; locals at or above it shift up by one and ASM recomputes the stack map frames.
- `depth + 1` wraps at `Integer.MAX_VALUE`, which is harmless because the mask test is periodic mod 2^32.
- Only calls to statically bound targets are redirected (`static`, `private`, `final`, or a `final` class), and only inside twins. A call to an overridable method, or through unannotated code, reaches the entry point. Inside a segment that does not start a new virtual thread: it counts the crossing on the shared counter (as the thread-local mode does) and restarts the parameter depth at 0. Each crossing therefore adds to the counter, so the stack between yields is bounded by about `interval` crossings of up to `interval` frames each in the worst case, not strictly `interval` frames.
- For instance methods the thunk is still static and takes `this` first. The thunk and the entry point call the twin with `invokespecial`, so `super.m()` from an override reaches this class's twin, not the override's.
- `synchronized` stays on the twin and is removed from the entry point.

### `Depth.THREAD_LOCAL`

```java
public class Node {
    final Node next;

    @StackSafe(depth = Depth.THREAD_LOCAL)
    public long length() { return next == null ? 1 : 1 + next.length(); }
}
```

becomes:

```java
public class Node {
    final Node next;

    // Entry point: same signature, annotations and generics. No call site anywhere is rewritten.
    public long length() {
        int[] d = StackSafeRuntime.DEPTH.get();       // one shared ThreadLocal; stored in the first free local; null outside a segment
        if (d == null)                                // outermost call: not on a stack-safe virtual thread yet
            return (Long) StackSafeRuntime.run(() -> length$ss$thunk(this));
        StackSafeRuntime.enter(d, 1023);              // if ((++d[0] & mask) == mask) Thread.yield();
        try {
            return length$ss$body();                  // invokespecial: this class's body, never an override
        } finally {                                   // an exception-table catch-all
            StackSafeRuntime.leave(d);                // d[0]--
        }
    }

    // The original method node, renamed and made private synthetic (public synthetic in interfaces).
    // Instructions are unchanged: no locals shift, and next.length() is an ordinary virtual call
    // to the entry point.
    private /*synthetic*/ long length$ss$body() {
        return next == null ? 1 : 1 + next.length();
    }

    // Outermost call only. Calls the body directly: run() has already set the thread's counter, and
    // re-entering length() would just add a level.
    private static /*synthetic*/ Object length$ss$thunk(Node self) {
        return Long.valueOf(self.length$ss$body());
    }
}
```

- Everything happens in the callee's prologue, so an unannotated override calling `super.length()` or a call through unannotated code is fine: the counter is per thread.
- `run()` does `DEPTH.set(new int[1])` on the new virtual thread before the body starts, which is what makes `d != null` mean "inside a segment". The parameter mode's `run` sets it too, so a thread-local method called from a parameter-mode twin doesn't start a new `run` per call.
- The thunk uses `invokespecial` so that a `super.length()` from an unannotated override doesn't dispatch back to the override.
- The `try` range covers only the body call, so a failure in `leave` can't re-trigger the handler; `leave` runs on both the normal and exception paths.
- Two frames per level (`length` and `length$ss$body`) against one in the parameter mode, so the same `interval` needs about twice the stack headroom.
- `static` uses `invokestatic` and has no `this`; `void` returns `RETURN` and boxes `null`; `private` uses `invokespecial`.

## Alternatives

All three work today without this project. Each is shown on the same `eval` over `Expr`.

### `-Xss`

```
java -Xss512m -jar app.jar        # sbt: -J-Xss512m
```

Sets the default stack size for every new Java thread, so it needs no code change. The costs: the size applies to all threads (every pool and GC-adjacent worker reserves that much address space, though pages are only committed when touched), you must still guess a depth up front, and deep stacks are scanned as GC roots at each collection. It is also the first thing to hit a limit in a container: the reservation counts against virtual-memory limits where they are enforced.

### Big-stack thread

```java
static long evalBig(Expr e) throws InterruptedException {
    long[] result = new long[1];
    Throwable[] failure = new Throwable[1];
    Thread t = new Thread(null, () -> {
        try { result[0] = eval(e); } catch (Throwable x) { failure[0] = x; }
    }, "big-stack", 1L << 30);                       // 1 GB
    t.start();
    t.join();
    if (failure[0] != null) throw new RuntimeException(failure[0]);
    return result[0];
}
```

The stack-size argument of `Thread` is a hint, but HotSpot honours it on the main platforms. It confines the reservation to one thread at a time, and it was competitive with the continuation approach in the spike (1.03 s against 0.81 s at 10M deep). It is still a fixed size you must pick in advance: at 60M deep the 1 GB stack overflowed. The caller has to carry over anything thread-bound (context class loader, thread-locals, interrupt handling), and the main thread and pool threads cannot be given a bigger stack after the fact.

### Manual rewrite with an explicit stack

Replace the call stack with a work list on the heap: the evaluator pushes the sub-work and a marker for what to do once the results are in, and a loop drives it.

```java
private enum Op { ADD, NEG }

static long eval(Expr root) {
    Deque<Object> work = new ArrayDeque<>();   // an Expr to evaluate, or an Op to apply
    Deque<Long> values = new ArrayDeque<>();   // results of finished sub-expressions
    work.push(root);
    while (!work.isEmpty()) {
        Object w = work.pop();
        if (w instanceof Op op) {
            switch (op) {
                case ADD -> { long right = values.pop(), left = values.pop(); values.push(left + right); }
                case NEG -> values.push(-values.pop());
            }
        } else {
            switch ((Expr) w) {
                case Lit l -> values.push(l.value());
                case Add a -> { work.push(Op.ADD); work.push(a.right()); work.push(a.left()); }
                case Neg n -> { work.push(Op.NEG); work.push(n.operand()); }
            }
        }
    }
    return values.pop();
}
```

This is the most portable option: it uses no thread tricks, has no depth limit but heap, and is the fastest of the four because it allocates nothing but the deque nodes. The cost is in the code: each recursive method becomes a state machine, continuation points need explicit markers (`Op` above), `try`/`finally` and exception handling have to be rebuilt by hand, and mutual recursion or recursion through virtual calls means every participant adopts the same scheme. It is also an invasive change in a codebase that already works for typical depths.

### Comparison

| | Code change | Depth bound | Per-thread cost | Notes |
|---|---|---|---|---|
| `-Xss` | none | fixed, guessed | every thread | simplest; GC scans deep stacks |
| Big-stack thread | wrapper at the entry point | fixed, guessed | one thread | hint only; thread-bound state to carry |
| Explicit stack | rewrite each recursive method | heap | none | fastest; most invasive |
| `@StackSafe` | annotation | heap | none | runs on a virtual thread; see [Caveats](#caveats) |

## Caveats

- **Programs that relied on `StackOverflowError` no longer terminate.** Cycle detection by catching SOE (cyclic object graphs, self-referential types, runaway macro or type-level expansion) becomes an infinite loop that eats heap until `OutOfMemoryError`. Annotate only methods whose input is known to be acyclic, or add an explicit visited-set or depth limit.
- **Failure moves from the stack to the heap.** Unbounded recursion now ends in `OutOfMemoryError`, usually after a long GC-heavy slowdown, not a fast SOE.
- **With the `virtual` backend the body runs on a virtual thread**, so `Thread.currentThread()` differs from the caller's, thread-locals the body reads or writes are not the caller's, and the context class loader may differ. Interrupts reach the caller; the transform waits for the segment to finish.
- **With the `virtual` backend, locks held by the caller are not held by the body.** If the caller holds a `ReentrantLock` or a monitor and the body tries to take it, it blocks on the caller, which is waiting for the body: a deadlock. Don't annotate methods that are entered while holding a lock the recursion also takes.
- **With the `virtual` backend, a pinned caller can starve the scheduler.** A caller that is itself a pinned virtual thread blocks its carrier while waiting for the body's virtual thread, which needs a carrier of its own. With the carriers all pinned this deadlocks.
- **`interval` frames must fit in the stack the segment runs on:** a carrier's for `virtual`, the caller's remaining stack for `continuation`, so a small pool thread already deep in its own work has less room. Too large an interval fails with `StackOverflowError` between yields (the spike hit this at 4096 with a 512K stack). The default of 1024 suits normal frame sizes; large frames need less. On the `continuation` backend the segment runs on the caller's stack, and 1024 already overflowed a 512 KB thread in the integration tests (256 was fine).
- **Pinning prevents yielding.** Native frames or critical sections make a yield do nothing (a no-op `Thread.yield()`, or the pinned `IllegalStateException` the continuation backend catches), so the recursion proceeds on the stack until the next interval at which it is no longer pinned. Pinning with the `continuation` backend and held monitors is untested.
- **The `continuation` backend is internal API.** It needs the `jdk.internal.vm` export and is validated only on the JDKs the tests have run on.
- **`PARAMETER` mode only redirects statically bound calls:** `static`, `private`, `final`, or methods of a `final` class. Other virtual calls still work but restart the parameter depth at the entry point (the agent warns). Use `THREAD_LOCAL` for those.
- **Crossings in `PARAMETER` mode** (unannotated or overridable code between annotated methods) restart the parameter depth, so the stack between yields can reach about `interval` squared frames in the worst case. They stay on the same virtual thread and count toward the shared counter. Use `THREAD_LOCAL` for a strict `interval` bound.
- **Constructors and abstract/native methods cannot be annotated** (ignored with a warning).
- **Stack traces and debuggers** show the synthetic `$ss`, `$ss$body` and `$ss$thunk` frames and the virtual thread's `run` boundary.
- **Class files are read as resources, not by loading classes**, so frame computation falls back to `Object` for types it cannot find.

## Tests

`mvn verify` runs three suites (last run: JDK 26, all passing):

| Suite | What it covers | Result |
|---|---|---|
| `StackSafeTransformerTest`, `virtual` backend | 22 tests through a transforming classloader on 1 MB-stack threads, interval 256: 2M-deep left-deep tree, mutual recursion, void/wide/object returns, exception types, both depth modes, mixed modes, overrides and `super` calls, interfaces, annotation and signature preservation, depth counter not leaking to the caller | 22 passed |
| `StackSafeTransformerTest`, `continuation` backend | The same 22 tests with `--add-exports` and `-Dstacksafe.backend=continuation`, including thread identity and caller thread-locals being visible | 22 passed |
| `AgentIT` (`verify` phase) | 5 end-to-end tests in a forked JVM with `-javaagent` on the shaded jar, running a 1M-deep recursion on a 512 KB-stack thread: default backend, `virtual` with `interval=256`, `continuation` with the agent doing the export (no `--add-exports`), the same program without the agent (fails with `StackOverflowError`), and bad agent options failing at startup | 5 passed |

Finding from the integration tests: with `backend=continuation` and the default `interval=1024`, the 512 KB stack overflows inside `Continuation.doYield` at the first yield; `interval=128` and `256` work. The `virtual` backend at 1024 on the same stack is fine, since its segment runs on a carrier thread's stack, not the caller's. So on the `continuation` backend, size `interval` to the caller's stack.

## Spike numbers

`spike/` holds the hand-written `$ss` form, measured on a left-deep `Add` chain (JDK 26, `-Xmx6g`, N=1024). `Continuation` is `jdk.internal.vm.Continuation` driven directly, which needs `--add-exports`.

| | 10M deep | 60M deep |
|---|---|---|
| `Continuation` | 0.81 s, 1.04 GB RSS | 5.5 s, 5.5 GB RSS |
| Virtual thread + `Thread.yield()` (**used here**) | 0.89 s, 1.05 GB RSS | 6.0 s, 5.7 GB RSS |
| Plain recursion, 1 GB stack thread | 1.03 s, 0.92 GB RSS | `StackOverflowError` |

Most of the RSS is the tree itself. At depths where the big stack fits, it is competitive; the pitch is heap-boundedness without having to size a stack up front.

## Future work

- Build-time transform (Maven plugin / CLI over a class directory) so the agent is optional.
- Transform-time interval choice from frame size, or retry with a smaller interval on `StackOverflowError`.
