# extractHost

Extract the hostname part of the `Host` request header and expose it as a `String` extraction
to its inner route.

@@@ div { .group-scala }

## Signature

@@signature [HostDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/HostDirectives.scala) { #extractHost }

@@@

## Example

Scala
:  @@snip [HostDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/HostDirectivesExamplesSpec.scala) { #extractHost }

Java
:  @@snip [HostDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/HostDirectivesExamplesTest.java) { #extractHostname }
