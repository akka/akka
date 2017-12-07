# withoutRequestTimeout

@@@ div { .group-scala }

## Signature

@@signature [TimeoutDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/TimeoutDirectives.scala) { #withoutRequestTimeout }

@@@

## Description

This directive enables "late" (during request processing) control over the @ref[Request timeout](../../../common/timeouts.md#request-timeout) feature in Akka HTTP.

It is not recommended to turn off request timeouts using this method as it is inherently racy and disabling request timeouts
basically turns off the safety net against programming mistakes that it provides.

@@@ warning
Please note that setting the timeout from within a directive is inherently racy (as the "point in time from which
we're measuring the timeout" is already in the past (the moment we started handling the request), so if the existing
timeout already was triggered before your directive had the chance to change it, an timeout may still be logged.
@@@

For more information about various timeouts in Akka HTTP see @ref[Akka HTTP Timeouts](../../../common/timeouts.md).

## Example

Scala
:  @@snip [TimeoutDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/TimeoutDirectivesExamplesSpec.scala) { #withoutRequestTimeout }

Java
:  @@snip [TimeoutDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/TimeoutDirectivesExamplesTest.java) { #withoutRequestTimeout-1 }
