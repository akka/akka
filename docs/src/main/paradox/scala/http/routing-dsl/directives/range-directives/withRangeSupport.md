# withRangeSupport

## Signature

```scala
def withRangeSupport(): Directive0
def withRangeSupport(rangeCountLimit: Int, rangeCoalescingThreshold:Long): Directive0
```

The signature shown is simplified, the real signature uses magnets. <a id="^1" href="#1">[1]</a>

> <a id="1" href="#^1">[1]</a> See [The Magnet Pattern](http://spray.io/blog/2012-12-13-the-magnet-pattern/) for an explanation of magnet-based overloading.

## Description

Transforms the response from its inner route into a `206 Partial Content`
response if the client requested only part of the resource with a `Range` header.

Augments responses to `GET` requests with an `Accept-Ranges: bytes` header and converts them into partial responses
if the request contains a valid `Range` request header. The requested byte-ranges are coalesced (merged) if they
lie closer together than the specified `rangeCoalescingThreshold` argument.

In order to prevent the server from becoming overloaded with trying to prepare `multipart/byteranges` responses for
high numbers of potentially very small ranges the directive rejects requests requesting more than `rangeCountLimit`
ranges with a @unidoc[TooManyRangesRejection].
Requests with unsatisfiable ranges are rejected with an @unidoc[UnsatisfiableRangeRejection].

The `withRangeSupport()` form (without parameters) uses the `range-coalescing-threshold` and `range-count-limit`
settings from the `akka.http.routing` configuration.

This directive is transparent to non-`GET` requests.

See also: [RFC 7233](https://tools.ietf.org/html/rfc7233)

## Example

Scala
:  @@snip [RangeDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/RangeDirectivesExamplesSpec.scala) { #withRangeSupport }

Java
:  @@snip [RangeDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/RangeDirectivesExamplesTest.java) { #withRangeSupport }
