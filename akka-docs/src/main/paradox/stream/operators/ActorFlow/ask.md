# ActorFlow.ask

Use the `AskPattern` to send each element as an `ask` to the target actor, and expect a reply back that will be sent further downstream.

@ref[Actor interop operators](../index.md#actor-interop-operators)

## Dependency

This operator is included in:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-stream-typed_$scala.binary_version$"
  version="$akka.version$"
}

@@@div { .group-scala }

## Signature

@@signature [ActorFlow.scala]($akka$/akka-stream-typed/src/main/scala/akka/stream/typed/scaladsl/ActorFlow.scala) { #ask }

@@@

## Description

Emit the contents of a file, as `ByteString`s, materializes into a @scala[`Future`] @java[`CompletionStage`] which will be completed with
a `IOResult` upon reaching the end of the file or if there is a failure.

## Examples


Scala
:  @@snip [ask.scala]($akka$/akka-stream-typed/src/test/scala/akka/stream/typed/scaladsl/ActorFlowSpec.scala) { #imports #ask-actor #ask }

Java
:   @@snip [ask.java]($akka$/akka-stream-typed/src/test/java/akka/stream/typed/javadsl/ActorFlowCompileTest.java) { #ask-actor #ask }

