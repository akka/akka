# ActorFlow.ask

Use the `AskPattern` to send each element as an `ask` to the target actor, and expect a reply back that will be sent further downstream.

@ref[Actor interop stages](../index.md#actor-interop-stages)

@@@div { .group-scala }

## Signature

@@signature [FileIO.scala]($akka$/akka-stream-typed/src/main/scala/akka/stream/typed/scaladsl/ActorFlow.scala) { #ask }

@@@

## Description

Emit the contents of a file, as `ByteString`s, materializes into a @scala[`Future`] @java[`CompletionStage`] which will be completed with
a `IOResult` upon reaching the end of the file or if there is a failure.
