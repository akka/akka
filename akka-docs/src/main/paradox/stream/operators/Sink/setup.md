# Sink.setup

Defer the creation of a `Sink` until materialization and access `ActorMaterializer` and `Attributes`

@ref[Sink operators](../index.md#sink-operators)

@@@ warning

The `setup` operator has been deprecated, use @ref:[fromMaterializer](./fromMaterializer.md) instead. 

@@@

## Signature

@apidoc[Sink.setup](Sink$) { scala="#setup[T,M](factory:(akka.stream.ActorMaterializer,akka.stream.Attributes)=&gt;akka.stream.scaladsl.Sink[T,M]):akka.stream.scaladsl.Sink[T,scala.concurrent.Future[M]]" java="#setup(java.util.function.BiFunction)" }

## Description

Typically used when access to materializer is needed to run a different stream during the construction of a sink.
Can also be used to access the underlying `ActorSystem` from `ActorMaterializer`.
