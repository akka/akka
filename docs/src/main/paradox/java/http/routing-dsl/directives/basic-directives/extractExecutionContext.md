# extractExecutionContext

@@@ div { .group-scala }

## Signature

@@signature [BasicDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractExecutionContext }

@@@

## Description

Extracts the `ExecutionContext` from the @unidoc[RequestContext].

See @ref[withExecutionContext](withExecutionContext.md) to see how to customise the execution context provided for an inner route.

See @ref[extract](extract.md) to learn more about how extractions work.

## Example

Scala
:  @@snip [BasicDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractExecutionContext-0 }

Java
:  @@snip [BasicDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java) { #extractExecutionContext }
