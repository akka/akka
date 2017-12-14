# handleExceptions

@@@ div { .group-scala }

## Signature

@@signature [ExecutionDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/ExecutionDirectives.scala) { #handleExceptions }

@@@

## Description

Catches exceptions thrown by the inner route and handles them using the specified @unidoc[ExceptionHandler].

Using this directive is an alternative to using a global implicitly defined @unidoc[ExceptionHandler] that
applies to the complete route.

See @ref[Exception Handling](../../exception-handling.md) for general information about options for handling exceptions.

## Example

Scala
:  @@snip [ExecutionDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/ExecutionDirectivesExamplesSpec.scala) { #handleExceptions }

Java
:  @@snip [ExecutionDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/ExecutionDirectivesExamplesTest.java) { #handleExceptions }
