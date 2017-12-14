# logRequestResult

## Description

Logs both, the request and the response.

This directive is a combination of @ref[logRequest](logRequest.md) and @ref[logResult](logResult.md).

See @ref[logRequest](logRequest.md) for the general description how these directives work.

## Example

@@snip [DebuggingDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/DebuggingDirectivesExamplesTest.java) { #logRequestResult }

## Longer Example

This example shows how to log the response time of the request using the Debugging Directive

Scala
:  @@snip [DebuggingDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/DebuggingDirectivesExamplesSpec.scala) { #logRequestResultWithResponseTime }

Java
:  @@snip [DebuggingDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/DebuggingDirectivesExamplesTest.java) { #logRequestResultWithResponseTime }
