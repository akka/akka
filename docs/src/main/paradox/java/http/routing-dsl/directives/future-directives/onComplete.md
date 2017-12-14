# onComplete

@@@ div { .group-scala }

## Signature

@@signature [FutureDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/FutureDirectives.scala) { #onComplete }

@@@

## Description

Evaluates its parameter of type `CompletionStage<T>`, and once it has been completed, extracts its
result as a value of type `Try<T>` and passes it to the inner route. A `Try<T>` can either be a `Success` containing
the `T` value or a `Failure` containing the `Throwable`.

To handle the `Failure` case automatically and only work with the result value, use @ref[onSuccess](onSuccess.md).

To complete with a successful result automatically and just handle the failure result, use @ref[completeOrRecoverWith](completeOrRecoverWith.md), instead.

## Example

@@snip [FutureDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/FutureDirectivesExamplesTest.java) { #onComplete }

@@@ note
Using the `onComplete` directive means that you'll have to explicitly and manually handle failure cases. Doing this for every route in your app will result in a lot of boilerplate code. Most of the time you want to use the @ref[Exception Handling](../../exception-handling.md) mechanism instead.
