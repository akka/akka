# watch

Watch a specific `ActorRef` and signal a failure downstream once the actor terminates.

@ref[Actor interop operators](../index.md#actor-interop-operators)

## Signature

@apidoc[Source.watch](Source) { scala="#watch(ref:akka.actor.ActorRef):FlowOps.this.Repr[Out]" java="#watch(akka.actor.ActorRef)" }
@apidoc[Flow.watch](Flow) { scala="#watch(ref:akka.actor.ActorRef):FlowOps.this.Repr[Out]" java="#watch(akka.actor.ActorRef)" }

## Description

Watch a specific `ActorRef` and signal a failure downstream once the actor terminates.
The signaled failure will be an @java[@javadoc:[WatchedActorTerminatedException](akka.stream.WatchedActorTerminatedException)]
@scala[@scaladoc[WatchedActorTerminatedException](akka.stream.WatchedActorTerminatedException)].

## Example

An `ActorRef` can be can be watched and the stream will fail with `WatchedActorTerminatedException` when the
actor terminates. 

Scala
:   @@snip [Watch.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/Watch.scala) { #watch }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #watch }


## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream emits

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

