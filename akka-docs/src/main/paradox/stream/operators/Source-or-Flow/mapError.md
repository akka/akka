# mapError

While similar to `recover` this operators can be used to transform an error signal to a different one *without* logging it as an error in the process.

@ref[Error handling](../index.md#error-handling)

## Signature

@apidoc[Source.mapError](Source) { scala="#mapError(pf:PartialFunction[Throwable,Throwable]):FlowOps.this.Repr[Out]" java="#mapError(java.lang.Class,akka.japi.function.Function)" }
@apidoc[Flow.mapError](Flow) { scala="#mapError(pf:PartialFunction[Throwable,Throwable]):FlowOps.this.Repr[Out]" java="#mapError(java.lang.Class,akka.japi.function.Function)" }


## Description

While similar to `recover` this operators can be used to transform an error signal to a different one *without* logging
it as an error in the process. So in that sense it is NOT exactly equivalent to `recover(t => throw t2)` since recover
would log the `t2` error.

Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
This operators can recover the failure signal, but not the skipped elements, which will be dropped.

Similarly to `recover` throwing an exception inside `mapError` _will_ be logged on ERROR level automatically.

## Example

The following example demonstrates a stream which throws `ArithmeticException` when the element `0` goes through 
the `map` operator. The`mapError` is used to transform this exception to `UnsupportedOperationException`.

Scala
:  @@snip [MapError.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/MapError.scala) { #map-error }

Java
:  @@snip [MapError.java](/akka-docs/src/test/java/jdocs/stream/operators/sourceorflow/MapError.java) { #map-error }

## Reactive Streams semantics

@@@div { .callout }

**emits** when element is available from the upstream or upstream is failed and pf returns an element
**backpressures** when downstream backpressures
**completes** when upstream completes or upstream failed with exception pf can handle

@@@

