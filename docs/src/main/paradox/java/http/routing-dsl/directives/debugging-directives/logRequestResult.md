<a id="logrequestresult-java"></a>
# logRequestResult

## Description

Logs both, the request and the response.

This directive is a combination of @ref[logRequest](logRequest.md#logrequest-java) and @ref[logResult-java](logResult.md#logresult-java).

See @ref[logRequest](logRequest.md#logrequest-java) for the general description how these directives work.

## Example

@@snip [DebuggingDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/DebuggingDirectivesExamplesTest.java) { #logRequestResult }

## Longer Example

This example shows how to log the response time of the request using the Debugging Directive

@@snip [DebuggingDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/DebuggingDirectivesExamplesTest.java) { #logRequestResultWithResponseTime }