# Release Notes

## 10.0.3

See the [announcement](http://akka.io/news/2017/01/26/akka-http-10.0.3-released.html) and
closed tickets on the [10.0.3 milestone](https://github.com/akka/akka-http/milestone/19?closed=1).

This release contains mostly bug fixes, a huge number of contributed documentation fixes and
small improvements.

### HttpApp

A notable new feature is the experimental `HttpApp` feature (long time users may know it from spray). It allows
to create an Akka HTTP server with very little boilerplate. See its @ref[Java](java/http/routing-dsl/HttpApp.md)
and @ref[Scala](scala/http/routing-dsl/HttpApp.md) documentation. Thanks a lot, [@jlprat](https://github.com/jlprat) for
contributing this feature and carrying through the PR with the most comments so far.

### Changed semantics

In some cases, we need to change semantics slightly to fix bugs. Some user code may still rely on the
previous behavior so we are listing them here to bring attention to potential issues.

 * `Uri.Authority.toString` now doesn't render leading double slashes any more ([#784](https://github.com/akka/akka-http/issues/784))
 * request URIs for requests coming in for a server set up to use TLS now carry the `https` scheme ([#658](https://github.com/akka/akka-http/issues/658))

### List of changes

#### Improvements

##### akka-http-core:

 * Optimize frame creation for streamed WS messages ([#748](https://github.com/akka/akka-http/issues/748))

##### akka-http:

 * Provide HttpApp API for Scala and Java ([#156](https://github.com/akka/akka-http/issues/156))
 * Add Java-side mapRejectionResponse ([#593](https://github.com/akka/akka-http/issues/593))
 * Add Composing directives java (`anyOf`, `allOf`). ([#620](https://github.com/akka/akka-http/issues/620))
 * Add Unmarshaller.andThen to combine two Unmarshallers ([#691](https://github.com/akka/akka-http/issues/691))

##### akka-http2-support:

 * Lots of larger and smaller improvements to Http2 support

#### Bugfixes

##### akka-http-core:

 * Allow Java bind API to specify port zero ([#660](https://github.com/akka/akka-http/issues/660))
 * Fix ConnectHttp.toHostHttps when no scheme is given
 * Exclude leading double slash from Uri.Authority.toString ([#784](https://github.com/akka/akka-http/issues/784))
 * Basic auth fix according to rfc7617 - 'charset' auth-param ([#716](https://github.com/akka/akka-http/issues/716))
 * Provide correct scheme in request URI for TLS connections ([#658](https://github.com/akka/akka-http/issues/658))
 * Prevent "Connection closed by peer" errors during connection closure ([#459](https://github.com/akka/akka-http/issues/459))

##### akka-http:

 * Fix stream marshalling, better errors, more examples ([#424](https://github.com/akka/akka-http/issues/424))
 * Don't ignore failed future for NoContent responses ([#589](https://github.com/akka/akka-http/issues/589))
 * Deprecate wrongly spelled method `Unmarshaller.unmarshall`
 * Match path maps in order of longest matching key prefix ([#394](https://github.com/akka/akka-http/issues/394))
 * Don't leak Scala classes in java Unmarshaller ([#604](https://github.com/akka/akka-http/issues/604))

##### akka-http-testkit:

 * Add support for Specs2 in testkit ([#485](https://github.com/akka/akka-http/issues/485))

##### akka-http-spray-json:

 * Fix spray-json unmarshalling of 4-byte UTF-8 characters AKA "the 😁 fix" ([#691](https://github.com/akka/akka-http/issues/691))
 * Updated spray-json dependency to 1.3.3

####  Documentation

 * A huge amount of bigger and smaller contributions from the community
 * Add link to sources to every documentation page to simplify contributing small fixes
 * Add search thanks to algolia ([#726](https://github.com/akka/akka-http/issues/726))


## 10.0.2

Security patch to prevent denial-of-service due to memory leak in server infrastructure.

See the [announcement](http://akka.io/news/2017/01/23/akka-http-10.0.2-security-fix-released.html),
@ref[Details](security/2017-01-23-denial-of-service-via-leak-on-unconsumed-closed-connections.md) and
[changes](https://github.com/akka/akka-http/compare/v10.0.1...v10.0.2).

## 10.0.1

See the [announcement](http://akka.io/news/2016/12/22/akka-http-10.0.1-released.html) and
closed tickets on the [10.0.1 milestone](https://github.com/akka/akka-http/milestone/17?closed=1)

## 10.0.0

See the [announcement](http://akka.io/news/2016/11/22/akka-http-10.0.0-released.html) and
closed tickets on the [10.0.0 milestone](https://github.com/akka/akka-http/milestone/14?closed=1)