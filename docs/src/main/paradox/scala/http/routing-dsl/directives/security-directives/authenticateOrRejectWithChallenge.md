# authenticateOrRejectWithChallenge

## Signature

@@signature [SecurityDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala) { #AuthenticationResult }

@@signature [SecurityDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala) { #authenticateOrRejectWithChallenge }

## Description

Lifts an authenticator function into a directive.

This directive allows implementing the low level challenge-response type of authentication that some services may require.

More details about challenge-response authentication are available in the [RFC 2617](http://tools.ietf.org/html/rfc2617), [RFC 7616](http://tools.ietf.org/html/rfc7616) and [RFC 7617](http://tools.ietf.org/html/rfc7617).

## Example

Scala
:  @@snip [SecurityDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/SecurityDirectivesExamplesSpec.scala) { #authenticateOrRejectWithChallenge-0 }

Java
:  @@snip [SecurityDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/SecurityDirectivesExamplesTest.java) { #authenticateOrRejectWithChallenge }
