# cachingProhibited

@@@ div { .group-scala }

## Signature

@@signature [CachingDirectives.scala](../../../../../../../../../akka-http-caching/src/main/scala/akka/http/scaladsl/server/directives/CachingDirectives.scala) { #cachingProhibited }

@@@

## Description

This directive is used to filter out requests that forbid caching. It is used as a building block of the @ref[cache](cache.md) directive to prevent caching if the client requests so.

## Example

Scala
:  @@snip [HeaderDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/CachingDirectivesExamplesSpec.scala) { #caching-prohibited }

Java
:  @@snip [CachingDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/CachingDirectivesExamplesTest.java) { #caching-prohibited }
