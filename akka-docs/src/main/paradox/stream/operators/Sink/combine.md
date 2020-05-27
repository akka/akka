# Sink.combine

Combine several sinks into one using a user specified strategy

@ref[Sink operators](../index.md#sink-operators)

## Signature

@apidoc[Sink.combine](Sink$) { scala="#combine[T,U](first:akka.stream.scaladsl.Sink[U,_],second:akka.stream.scaladsl.Sink[U,_],rest:akka.stream.scaladsl.Sink[U,_]*)(strategy:Int=&gt;akka.stream.Graph[akka.stream.UniformFanOutShape[T,U],akka.NotUsed]):akka.stream.scaladsl.Sink[T,akka.NotUsed]" java="#combine(akka.stream.javadsl.Sink,akka.stream.javadsl.Sink,java.util.List,akka.japi.function.Function)" }

## Description

Combine several sinks into one using a user specified strategy

## Reactive Streams semantics

@@@div { .callout }

**cancels** depends on the strategy

**backpressures** depends on the strategy

@@@

