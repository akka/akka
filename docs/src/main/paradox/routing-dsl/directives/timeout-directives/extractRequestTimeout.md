# extractRequestTimeout

@@@ div { .group-scala }

## Signature

@@signature [TimeoutDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/TimeoutDirectives.scala) { #extractRequestTimeout }

@@@

## Description

This directive extracts the currently set request timeout. 

@@@ warning
Please note that this extracts the request timeout at the current moment, but the timeout can be changed concurrently. 
See other timeout directives about raciness inherent to timeout directives. 
@@@

For more information about various timeouts in Akka HTTP see @ref[Akka HTTP Timeouts](../../../common/timeouts.md).

## Example

Scala
:  @@snip [TimeoutDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/TimeoutDirectivesExamplesSpec.scala) { #extractRequestTimeout }

Java
:  @@snip [TimeoutDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/TimeoutDirectivesExamplesTest.java) { #extractRequestTimeout }
