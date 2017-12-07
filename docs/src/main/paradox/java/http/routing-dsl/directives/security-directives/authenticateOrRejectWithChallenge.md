# authenticateOrRejectWithChallenge

Lifts an authenticator function into a directive.

## Description

This directive allows implementing the low level challenge-response type of authentication that some services may require.

More details about challenge-response authentication are available in the [RFC 2617](http://tools.ietf.org/html/rfc2617), [RFC 7616](http://tools.ietf.org/html/rfc7616) and [RFC 7617](http://tools.ietf.org/html/rfc7617).

## Example

Scala
:  @@snip [SecurityDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/SecurityDirectivesExamplesSpec.scala) { #authenticateOrRejectWithChallenge-0 }

Java
:  @@snip [SecurityDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/SecurityDirectivesExamplesTest.java) { #authenticateOrRejectWithChallenge }
