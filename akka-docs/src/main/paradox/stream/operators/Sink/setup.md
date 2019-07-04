# Sink.setup

Defer the creation of a `Sink` until materialization and access `ActorMaterializer` and `Attributes`

@ref[Sink operators](../index.md#sink-operators)

@@@ div { .group-scala }

## Signature

@@signature [Sink.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #setup }
@@@

## Description

Typically used when access to materializer is needed to run a different stream during the construction of a sink.
Can also be used to access the underlying `ActorSystem` from `ActorMaterializer`.