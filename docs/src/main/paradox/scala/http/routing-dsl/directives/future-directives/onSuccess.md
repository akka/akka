# onSuccess

@@@ div { .group-scala }

## Signature

@@signature [FutureDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/FutureDirectives.scala) { #onSuccess }

@@@

## Description

Evaluates its parameter of type `Future[T]`, and once the `Future` has been completed successfully,
extracts its result as a value of type `T` and passes it to the inner route.

If the future fails its failure throwable is bubbled up to the nearest @unidoc[ExceptionHandler].

To handle the `Failure` case manually as well, use @ref[onComplete](onComplete.md), instead.

## Example

Scala
:  @@snip [FutureDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/FutureDirectivesExamplesSpec.scala) { #onSuccess }

Java
:  @@snip [FutureDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/FutureDirectivesExamplesTest.java) { #onSuccess }
