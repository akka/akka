<a id="withexecutioncontext"></a>
# withExecutionContext

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #withExecutionContext }

## Description

Allows running an inner route using an alternative `ExecutionContext` in place of the default one.

The execution context can be extracted in an inner route using @ref[extractExecutionContext](extractExecutionContext.md#extractexecutioncontext) directly,
or used by directives which internally extract the materializer without surfacing this fact in the API.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #withExecutionContext-0 }