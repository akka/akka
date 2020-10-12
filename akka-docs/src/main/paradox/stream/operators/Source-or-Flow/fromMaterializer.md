# fromMaterializer

Defer the creation of a `Source/Flow` until materialization and access `Materializer` and `Attributes`

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.fromMaterializer](Source$) { scala="#fromMaterializer[T,M](factory:(akka.stream.Materializer,akka.stream.Attributes)=&gt;akka.stream.scaladsl.Source[T,M]):akka.stream.scaladsl.Source[T,scala.concurrent.Future[M]]" java="#fromMaterializer(java.util.function.BiFunction)" }
@apidoc[Flow.fromMaterializer](Flow$) { scala="#fromMaterializer[T,U,M](factory:(akka.stream.Materializer,akka.stream.Attributes)=&gt;akka.stream.scaladsl.Flow[T,U,M]):akka.stream.scaladsl.Flow[T,U,scala.concurrent.Future[M]]" java="#fromMaterializer(java.util.function.BiFunction)" }


## Description

Typically used when access to materializer is needed to run a different stream during the construction of a source/flow.
Can also be used to access the underlying `ActorSystem` from `Materializer`.
