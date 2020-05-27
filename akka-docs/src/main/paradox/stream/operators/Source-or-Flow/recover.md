# recover

Allow sending of one last element downstream when a failure has happened upstream.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.recover](Source) { scala="#recover[T&gt;:Out](pf:PartialFunction[Throwable,T]):FlowOps.this.Repr[T]" java="#recover(scala.PartialFunction)" java="#recover(java.lang.Class,java.util.function.Supplier)" }
@apidoc[Flow.recover](Flow) { scala="#recover[T&gt;:Out](pf:PartialFunction[Throwable,T]):FlowOps.this.Repr[T]" java="#recover(scala.PartialFunction)" java="#recover(java.lang.Class,java.util.function.Supplier)" }


## Description

`recover` allows you to emit a final element and then complete the stream on an upstream failure.
Deciding which exceptions should be recovered is done through a `PartialFunction`. If an exception
does not have a @scala[matching case] @java[match defined] the stream is failed. 

Recovering can be useful if you want to gracefully complete a stream on failure while letting 
downstream know that there was a failure.

Throwing an exception inside `recover` _will_ be logged on ERROR level automatically.

## Reactive Streams semantics

@@@div { .callout }

**emits** when the element is available from the upstream or upstream is failed and pf returns an element

**backpressures** when downstream backpressures, not when failure happened

**completes** when upstream completes or upstream failed with exception pf can handle

@@@

Below example demonstrates how `recover` gracefully complete a stream on failure. 
  
Scala
:   @@snip [FlowErrorDocSpec.scala](/akka-docs/src/test/scala/docs/stream/FlowErrorDocSpec.scala) { #recover }

Java
:   @@snip [FlowErrorDocTest.java](/akka-docs/src/test/java/jdocs/stream/FlowErrorDocTest.java) { #recover }

This will output:

Scala
:   @@snip [FlowErrorDocSpec.scala](/akka-docs/src/test/scala/docs/stream/FlowErrorDocSpec.scala) { #recover-output }

Java
:   @@snip [FlowErrorDocTest.java](/akka-docs/src/test/java/jdocs/stream/FlowErrorDocTest.java) { #recover-output }

The output in the line `before failure` denotes the last successful element available from the upstream, 
and the output in the line `on failure` denotes the element returns by partial function when upstream is failed.
