<a id="pass"></a>
# pass

## Signature

@@signature [BasicDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala) { #pass }

## Description

A directive that passes the request unchanged to its inner route.

It is usually used as a "neutral element" when combining directives generically.

## Example

@@snip [BasicDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala) { #pass }