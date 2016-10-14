<a id="rejectemptyresponse"></a>
# rejectEmptyResponse

## Signature

@@signature [MiscDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/MiscDirectives.scala) { #rejectEmptyResponse }

## Description

Replaces a response with no content with an empty rejection.

The `rejectEmptyResponse` directive is mostly used with marshalling `Option[T]` instances. The value `None` is
usually marshalled to an empty but successful result. In many cases `None` should instead be handled as
`404 Not Found` which is the effect of using `rejectEmptyResponse`.

## Example

@@snip [MiscDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala) { #rejectEmptyResponse-example }