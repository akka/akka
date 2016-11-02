<a id="handleexceptions"></a>
# handleExceptions

## Signature

@@signature [ExecutionDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/ExecutionDirectives.scala) { #handleExceptions }

## Description

Catches exceptions thrown by the inner route and handles them using the specified `ExceptionHandler`.

Using this directive is an alternative to using a global implicitly defined `ExceptionHandler` that
applies to the complete route.

See @ref[Exception Handling](../../exception-handling.md#exception-handling-scala) for general information about options for handling exceptions.

## Example

@@snip [ExecutionDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/ExecutionDirectivesExamplesSpec.scala) { #handleExceptions }