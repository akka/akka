# Sink.preMaterialize

Materializes this Sink, immediately returning (1) its materialized value, and (2) a new Sink that can be consume elements 'into' the pre-materialized one.

@ref[Sink operators](../index.md#sink-operators)

@@@div { .group-scala }

## Signature

@@signature [Sink.scala](/akka-stream/src/main/scala/akka/stream/scaladsl/Sink.scala) { #preMaterialize }

@@@

## Description

Materializes this Sink, immediately returning (1) its materialized value, and (2) a new Sink that can be consume elements 'into' the pre-materialized one. Useful for when you need a materialized value of a Sink when handing it out to someone to materialize it for you.

