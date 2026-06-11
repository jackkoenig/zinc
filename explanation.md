# Crashes from stale intermediate class files in the first compile cycle

## The bug

Zinc's incremental algorithm normally propagates invalidation *outward, cycle by
cycle*: cycle 1 recompiles the classes whose sources changed, detects their API
changes, and only then invalidates their dependents (cycle 2), then *their*
dependents (cycle 3), and so on. A class is therefore never compiled against a
dependency's class file that is stale with respect to this run — by the time a
dependent is compiled, its dependencies have already been recompiled in an
earlier cycle.

Some classes, however, are forced into the **first** cycle without having changed
themselves:

1. **Removed products.** A class file was deleted out from under Zinc (e.g. by a
   build state restore that removes some products while keeping the analysis).
   The producing source must be recompiled immediately — its class file is gone.
2. **Same-source housemates.** Source files are the unit of recompilation, so
   every class defined in a modified source is recompiled in cycle 1, including
   classes that didn't themselves change.

Such a class — call it a *reader* — may depend on an **intermediate** class that
sits on a dependency path between the reader and a changed class. The
intermediate won't be recompiled until a later cycle (after the API change is
detected), so during cycle 1 its class file is stale: it can still refer to the
*old* API of the changed class. When scalac compiles the reader, it reads that
stale class file and can fail or even crash.

### Example 1: removed product

```scala
// Wrapper.scala — changes from this:
class Wrapper[T, U](val x: T)
// ...to this:
class Wrapper[T](val x: T)

// B.scala — unchanged; U is inferred as Nothing, so B.class
// records getW: Wrapper[Int, Nothing]
class B { def getW = new Wrapper(1) }

// C.scala — unchanged, but C.class was deleted externally
class C { val w = (new B).getW }
```

Initial invalidation contains only `Wrapper` (modified source) and `C` (removed
product). `C` can't wait for the invalidation to reach `B` in a later cycle —
its class file is gone — so cycle 1 compiles `C` against the stale `B.class`:

```
C.scala:1:15: type arguments [Int,Nothing] do not conform to class Wrapper's type parameter bounds [T]
```

### Example 2: same-source housemate

```scala
// A.scala — changes from this:
abstract class A
object Test { new B }
// ...to this:
trait A
object Test { new B }

// B.scala — unchanged
class B extends A
```

Only `A.scala` is invalidated, so cycle 1 compiles `A` *and* `Test` (same file).
`Test` reads the stale `B.class`, whose superclass is still the old
abstract-class `A`, and scalac crashes:

```
Error while emitting A.scala
assertion failed: Bad superClass for class B: trait A
```

In both cases the user sees an error pointing at sources that are perfectly
consistent.

## The fix

Zinc already knows everything it needs *before* the first cycle: which classes
changed, which classes are forced into cycle 1 as readers, and the internal
dependency relations connecting them. `invalidateInitial` now preemptively
invalidates the **stale intermediates**: classes on an internal dependency path
(over the `memberRef` relation) between a *reader* and a *changed* class, where

- readers are the removed-product classes and the classes of modified sources,
  and
- changed classes are everything initially invalidated (modified, removed,
  invalidated by external API changes or changed libraries).

These intermediates are recompiled from source in cycle 1 alongside everything
else, so no stale class file is ever read. In example 1 this adds exactly `B`;
in example 2 it likewise adds exactly `B`.

### The distinctness rule

Crucially, an intermediate only qualifies if the reader and the changed class
connected through it are **distinct**. Without this rule, every dependency
*cycle* would be preemptively recompiled on every edit: change only
`X.scala` in

```scala
class X { def x: Y = null; def impl = 1 }   // implementation-only change
class Y { def y: X = null }
```

and `Y` lies on a path from `X` back to `X`. Recompiling `Y` here would punish
every implementation-only edit of any class that participates in a dependency
cycle — a common shape — to defend against a much rarer hazard. Self-paths are
therefore excluded; only paths between two distinct first-cycle classes count.
(A regression test, "not recompile all files in a cycle for non-API changes",
pins this behavior.)

### Finding intermediates efficiently

Deciding "does a distinct (reader, changed) pair connect through this class?"
naively requires per-pair path searches. Instead, the fix propagates *root
labels* through the dependency graph in both directions — which readers reach
each class going forward, and which changed classes it reaches going backward —
**capped at two distinct labels per class**. Two labels are enough: label sets
of size one are exact, and any two distinct labels witness a distinct pair. The
cap makes the traversal linear in the number of dependency edges (each class is
re-enqueued at most twice). A class qualifies as a stale intermediate when its
forward and backward label sets are both non-empty and their union contains at
least two distinct classes.

Properties of this approach:

- The dominant incremental path — editing sources that each define a single
  class, with no products removed — computes nothing new and behaves exactly as
  before (a single root label can never form a distinct pair).
- No behavior change for ordinary compile errors; nothing is caught or retried.
- No errors are shown to the user and then retracted.

## Why not catch-and-retry?

An earlier version of this fix caught `CompileFailed` in
`IncrementalCommon.cycle`, expanded the invalidation set by direct dependents,
reset the reporter, and retried. That worked, but had significant downsides:

- **Genuine compile errors got retried.** A user typo in a class with dependents
  would trigger up to O(dependency-depth) failed compile cycles of growing size
  before the error was finally reported.
- **Errors were shown to the user and then retracted**, and the user-visible
  reporter had to be reset between attempts (otherwise the cached scalac
  instance short-circuits on `hasErrors`), which required plumbing a reset
  callback through `CompileCycle` and `IncrementalCompilerImpl`.
- It treated a predictable situation as an unpredictable failure. Since the
  at-risk classes are computable up front, recovery machinery is unnecessary.

The preemptive fix needs none of that: `Incremental.scala` and
`IncrementalCompilerImpl.scala` are untouched, and the whole change lives in
`invalidateInitial` plus one graph helper in `IncrementalCommon`.

## Tests

Three tests in `IncrementalCompilerSpec` pin the behavior:

- "compile against stale intermediate class files after a product is deleted" —
  example 1. Reproduces with a single project and three one-line sources; no
  second subproject or `ExternalLookup` hook is needed, because initial
  invalidation from a modified source only ever invalidates the classes
  *defined in* that source, never their dependents, so the stale intermediate
  arises naturally. (Subtlety: the test sets `withRecompileAllFraction(1.0)`;
  with only three sources, the default fraction would escalate to a full
  recompile and mask the bug.)
- "recompile subclass when superclass changes from abstract class to trait" —
  example 2.
- "not recompile all files in a cycle for non-API changes" — the guard rail for
  the distinctness rule (no over-invalidation of dependency cycles).

## Known limitations

- **Newly added sources are readers that cannot be protected preemptively.** An
  added source is compiled in cycle 1, but Zinc has no previous relations for it
  at all, so the at-risk intermediates between it and the changed classes are
  unknowable. Adding `C.scala` (using `B`) in the same run that changes
  `Wrapper` reproduces the example-1 error with no class file deleted
  (verified experimentally). This is pre-existing behavior, unchanged by this
  fix; the only mechanisms that could cover it are reactive (retry after
  failure) or wholesale (invalidating the full dependent cone of every change).
- **Dependency self-cycles**: by design the rule does not chase paths between a
  changed class and itself, so an incompatible API change to a class in a
  dependency *cycle* can still break cycle 1 (compiling the changed class reads
  the stale class file of its cyclic partner). This is unchanged, pre-existing
  behavior; handling it preemptively is impossible without recompiling cycles on
  every edit (see the distinctness rule).
- If a class file is stale but nothing Zinc can observe changed at all (e.g. an
  analysis file restored against class files from an entirely different build
  state, with no source, library, or upstream API change), there is no signal to
  drive any invalidation, and no invalidation-based fix can help. Handling that
  would require distrusting the analysis itself (i.e. a full recompile).
