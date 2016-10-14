<a id="extractexecutioncontext"></a>
# extractExecutionContext

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #extractExecutionContext }

## Description

Extracts the `ExecutionContext` from the `RequestContext`.

See @ref[withExecutionContext](withExecutionContext.md#withexecutioncontext) to see how to customise the execution context provided for an inner route.

See @ref[extract](extract.md#extract) to learn more about how extractions work.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #extractExecutionContext-0 }