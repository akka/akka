# reduce

Start with first element and then apply the current and next value to the given function, when upstream complete the current value is emitted downstream.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.reduce](Source) { scala="#reduce[T&gt;:Out](f:(T,T)=&gt;T):FlowOps.this.Repr[T]" java="#reduce(akka.japi.function.Function2)" }
@apidoc[Flow.reduce](Flow) { scala="#reduce[T&gt;:Out](f:(T,T)=&gt;T):FlowOps.this.Repr[T]" java="#reduce(akka.japi.function.Function2)" }


## Description

Start with first element and then apply the current and next value to the given function, when upstream
complete the current value is emitted downstream. Similar to `fold`.

## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream completes

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

