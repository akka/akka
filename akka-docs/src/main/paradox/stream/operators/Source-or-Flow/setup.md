# Source/Flow.setup

Defer the creation of a `Source/Flow` until materialization and access `Materializer` and `Attributes`

@ref[Simple operators](../index.md#simple-operators)

@@@ warning

The `setup` operator has been deprecated, use @ref:[fromMaterializer](./fromMaterializer.md) instead. 

@@@

@@@ div { .group-scala }

## Signature

@@signature [Source.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala) { #setup }
@@signature [Flow.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Flow.scala) { #setup }

@@@

## Description

Typically used when access to materializer is needed to run a different stream during the construction of a source/flow.
Can also be used to access the underlying `ActorSystem` from `ActorMaterializer`.