# conditional

@@@ div { .group-scala }

## Signature

@@signature [CacheConditionDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/CacheConditionDirectives.scala) { #conditional }

@@@

## Description

Wraps its inner route with support for Conditional Requests as defined
by [http://tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26](http://tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26).

Depending on the given `eTag` and `lastModified` values this directive immediately responds with
`304 Not Modified` or `412 Precondition Failed` (without calling its inner route) if the request comes with the
respective conditional headers. Otherwise the request is simply passed on to its inner route.

The algorithm implemented by this directive closely follows what is defined in [this section](http://tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26#section-6) of the
[HTTPbis spec](https://datatracker.ietf.org/wg/httpbis/).

All responses (the ones produces by this directive itself as well as the ones coming back from the inner route) are
augmented with respective @unidoc[ETag] and `Last-Modified` response headers.

Since this directive requires the @unidoc[EntityTag] and `lastModified` time stamp for the resource as concrete arguments
it is usually used quite deep down in the route structure (i.e. close to the leaf-level), where the exact resource
targeted by the request has already been established and the respective ETag/Last-Modified values can be determined.

The @ref[FileAndResourceDirectives](../file-and-resource-directives/index.md) internally use the `conditional` directive for ETag and Last-Modified support
(if the `akka.http.routing.file-get-conditional` setting is enabled).