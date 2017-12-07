# cache

@@@ div { .group-scala }

## Signature

@@signature [CachingDirectives.scala](../../../../../../../../../akka-http-caching/src/main/scala/akka/http/scaladsl/server/directives/CachingDirectives.scala) { #cache }

@@@

## Description

Wraps its inner Route with caching support using the given @unidoc[Cache] implementation and the provided keyer function.

The directive tries to serve the request from the given cache and only if not found runs the inner route to generate a new response. A simple cache can be constructed using `routeCache` constructor.

The directive is implemented in terms of @ref[cachingProhibited](cachingProhibited.md) and @ref[alwaysCache](alwaysCache.md). This means that clients can circumvent the cache using a `Cache-Control` request header. This behavior may not be adequate depending on your backend implementation (i.e how expensive a call circumventing the cache into the backend is). If you want to force all requests to be handled by the cache use the @ref[alwaysCache](alwaysCache.md) directive instead. In complexer cases, e.g. when the backend can validate that a cached request is still acceptable according to the request `Cache-Control` header the predefined caching directives may not be sufficient and a custom solution is necessary.

## Example

Scala
:  @@snip [CachingDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/CachingDirectivesExamplesSpec.scala) { #cache }

Java
:  @@snip [CachingDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/CachingDirectivesExamplesTest.java) { #cache }
