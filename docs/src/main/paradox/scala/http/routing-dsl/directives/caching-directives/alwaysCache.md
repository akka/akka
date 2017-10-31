# alwaysCache

## Signature

@@signature [CachingDirectives.scala](../../../../../../../../../akka-http-caching/src/main/scala/akka/http/scaladsl/server/directives/CachingDirectives.scala) { #alwaysCache }

## Description

Like @ref[cache](cache.md) but disregards a `Cache-Control` request header.

## Example

@@snip [CachingDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/CachingDirectivesExamplesSpec.scala) { #always-cache }
