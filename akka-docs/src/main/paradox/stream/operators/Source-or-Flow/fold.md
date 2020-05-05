# fold

Start with current value `zero` and then apply the current and next value to the given function. When upstream completes, the current value is emitted downstream.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.fold](Source) { scala="#fold[T](zero:T)(f:(T,Out)=&gt;T):FlowOps.this.Repr[T]" java="#fold(java.lang.Object,akka.japi.function.Function2)" }
@apidoc[Flow.fold](Flow) { scala="#fold[T](zero:T)(f:(T,Out)=&gt;T):FlowOps.this.Repr[T]" java="#fold(java.lang.Object,akka.japi.function.Function2)" }

## Description

Start with current value `zero` and then apply the current and next value to the given function. When upstream
completes, the current value is emitted downstream.

@@@ warning

Note that the `zero` value must be immutable, because otherwise
the same mutable instance would be shared across different threads
when running the stream more than once.

@@@

## Example

`fold` is typically used to 'fold up' the incoming values into an aggregate. For example, you might want to summarize the incoming values into a histogram:

Scala
:   @@snip [Fold.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Fold.scala) { #imports #histogram #fold }

Java
:   @@snip [Fold.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #fold }

## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream completes

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

