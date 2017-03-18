<a id="cachingprohibited"></a>
# cachingProhibited

## Signature

@@signature [CachingDirectives.scala](../../../../../../../../../akka-http-caching/src/main/scala/akka/http/scaladsl/server/directives/CachingDirectives.scala) { #cachingProhibited }

## Description

This directive is used to filter out requests that forbid caching. It is used as a building block of the @ref[cache](cache.md#cache) directive to prevent caching if the client requests so.

## Example

@@snip [HeaderDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/CachingDirectivesExamplesSpec.scala) { #caching-prohibited }