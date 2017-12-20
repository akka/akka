# completeOrRecoverWith

@@@ div { .group-scala }

## Signature

@@signature [FutureDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/FutureDirectives.scala) { #completeOrRecoverWith }

@@@

## Description

If the @scala[`Future[T]`]@java[`CompletionStage<T>`] succeeds the request is completed using the value's marshaller (this directive therefore
requires a marshaller for the @scala[future's parameter]@java[completion stage value] type to be @scala[implicitly available]@java[provided]). The execution of the inner
route passed to this directive is only executed if the given @scala[future]@java[completion stage] completed with a failure,
exposing the reason of failure as an extraction of type `Throwable`.

To handle the successful case manually as well, use the @ref[onComplete](onComplete.md) directive, instead.

## Example

Scala
:  @@snip [FutureDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/FutureDirectivesExamplesSpec.scala) { #completeOrRecoverWith }

Java
:  @@snip [FutureDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/FutureDirectivesExamplesTest.java) { #completeOrRecoverWith }
