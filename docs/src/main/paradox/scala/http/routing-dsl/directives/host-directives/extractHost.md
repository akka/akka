# extractHost

## Signature

@@signature [HostDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/HostDirectives.scala) { #extractHost }

## Description

Extract the hostname part of the `Host` request header and expose it as a `String` extraction to its inner route.

## Example

@@snip [HostDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/HostDirectivesExamplesSpec.scala) { #extractHost }