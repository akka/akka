<a id="handlerejections"></a>
# handleRejections

## Signature

@@signature [ExecutionDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/ExecutionDirectives.scala) { #handleRejections }

## Description

Using this directive is an alternative to using a global implicitly defined `RejectionHandler` that
applies to the complete route.

See @ref[Rejections](../../rejections.md#rejections-scala) for general information about options for handling rejections.

## Example

@@snip [ExecutionDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/ExecutionDirectivesExamplesSpec.scala) { #handleRejections }