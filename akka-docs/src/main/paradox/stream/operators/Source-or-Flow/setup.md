# setup

Defer the creation of a `Source/Flow` until materialization and access `Materializer` and `Attributes`

@ref[Simple operators](../index.md#simple-operators)

@@@ warning

The `setup` operator has been deprecated, use @ref:[fromMaterializer](./fromMaterializer.md) instead. 

@@@

## Signature

@apidoc[Source.setup](Source$) { scala="#setup[T,M](factory:(akka.stream.ActorMaterializer,akka.stream.Attributes)=&gt;akka.stream.scaladsl.Source[T,M]):akka.stream.scaladsl.Source[T,scala.concurrent.Future[M]]" java="#setup(java.util.function.BiFunction)" }
@apidoc[Flow.setup](Flow$) { scala="#setup[T,U,M](factory:(akka.stream.ActorMaterializer,akka.stream.Attributes)=&gt;akka.stream.scaladsl.Flow[T,U,M]):akka.stream.scaladsl.Flow[T,U,scala.concurrent.Future[M]]" java="#setup(java.util.function.BiFunction)" }

## Description

Typically used when access to materializer is needed to run a different stream during the construction of a source/flow.
Can also be used to access the underlying `ActorSystem` from `ActorMaterializer`.
