# withRequestTimeout

@@@ div { .group-scala }

## Signature

@@signature [TimeoutDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/TimeoutDirectives.scala) { #withRequestTimeout }

@@@

## Description

This directive enables "late" (during request processing) control over the @ref[Request timeout](../../../common/timeouts.md#request-timeout) feature in Akka HTTP.

The timeout can be either loosened or made more tight using this directive, however one should be aware that it is
inherently racy (which may especially show with very tight timeouts) since a timeout may already have been triggered
when this directive executes.

In case of pipelined HTTP requests (multiple requests being accepted on the same connection before sending the first response)
a the request timeout failure of the `n-th` request *will shut down the connection* causing the already enqueued requests
to be dropped. This is by-design, as the request timeout feature serves as a "safety net" in case of programming errors
(e.g. a Future that never completes thus potentially blocking the entire connection forever) or malicious attacks on the server.

Optionally, a timeout handler may be provided in which is called when a time-out is triggered and must produce an
@unidoc[HttpResponse] that will be sent back to the client instead of the "too late" response (in case it'd ever arrive).
See also @ref[withRequestTimeoutResponse](withRequestTimeoutResponse.md) if only looking to customise the timeout response without changing the timeout itself.

@@@ warning

Please note that setting the timeout from within a directive is inherently racy (as the "point in time from which
we're measuring the timeout" is already in the past (the moment we started handling the request), so if the existing
timeout already was triggered before your directive had the chance to change it, an timeout may still be logged.

It is recommended to use a larger statically configured timeout (think of it as a "safety net" against programming errors
or malicious attackers) and if needed tighten it using the directives – not the other way around.

@@@

For more information about various timeouts in Akka HTTP see @ref[Akka HTTP Timeouts](../../../common/timeouts.md).

## Example

@@snip [TimeoutDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/TimeoutDirectivesExamplesTest.java) { #withRequestTimeout-plain }

With setting the handler at the same time:

Scala
:  @@snip [TimeoutDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/TimeoutDirectivesExamplesSpec.scala) { #withRequestTimeout-with-handler }

Java
:  @@snip [TimeoutDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/TimeoutDirectivesExamplesTest.java) { #withRequestTimeout-with-handler }
