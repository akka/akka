# logResult

## Description

Logs the response.

See @ref[logRequest](logRequest.md) for the general description how these directives work.

Use `logRequest` for logging the request, or `logRequestResult` for logging both.

## Example

Scala
:  @@snip [DebuggingDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/DebuggingDirectivesExamplesSpec.scala) { #logResult }

Java
:  @@snip [DebuggingDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/DebuggingDirectivesExamplesTest.java) { #logResult }
