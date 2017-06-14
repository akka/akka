# Release Notes

## 10.0.8

### Https Proxy Support

Long awaited support for configuring an @ref[HTTPS CONNECT Proxy](scala/http/client-side/client-transport.md#use-https-proxy-with-http-singlerequest)
for the client has arrived. Thanks a lot, [Michal Sitko (@note)](https://github.com/note), who implemented the feature.

### Ability to express textual content types with missing character set

Akka-http has a strongly typed media type / content type system, and knows at compile time about which media types
are supposed to express a character set attribute, e.g. `text/plain; charset=UTF-8`. Before this release, akka would
silently assume UTF-8 for `ContentType` instances of media types with a missing `charset` attribute.

From now on, content types missing a charset can be both parsed and expressed directly, using the new 
`ContentType.WithMissingCharset` trait/class. 

- For incoming Content-Type headers with values missing a charset, such as `text/plain`, the header 
  `ContentType` will be represented as `WithMissingCharset`, rather than assuming an UTF-8 charset 
  (which could have been a wrong guess).
- If you need to create such a content type programmatically, use e.g. ```MediaTypes.`text/plain`.withMissingCharset```
  (scala) or `MediaTypes.TEXT_PLAIN.toContentTypeWithMissingCharset()` (java).

*Note to scala users*: If you have `match` statements across `ContentType`, keep an eye out for new compiler hints. You need
to decide what what to do in case you get a content type with a missing character set, by adding a 
`ContentType.WithMissingCharset` case. 

## 10.0.7

### New Seed Templates for Akka HTTP Apps

We prepared new seed templates for starting out with Akka HTTP using the [Java DSL](https://github.com/akka/akka-http-java-seed.g8)
as well as [Scala DSL](https://github.com/akka/akka-http-scala-seed.g8). By using the `sbt new` command one can now easily get a
small sample project to easily get started with your first Akka HTTP app. More instructions on the seed template pages.

### New Path Directive `ignoreTrailingSlash`

Akka HTTP treats differently by default a route that ends with slash (`/`) than one that doesn't. From this version on,
users who don't want to have this distinction, can use a new Path Directive called `ignoreTrailingSlash`.
This route, will retry its inner route with and without a trailing slash. If you want to know more about this feature,
please check the documentation pages for @ref[Scala](scala/http/routing-dsl/directives/path-directives/ignoreTrailingSlash.md)
and @ref[Java](java/http/routing-dsl/directives/path-directives/ignoreTrailingSlash.md) API.

### List of Changes

#### Improvements

##### akka-http
 * Added new Path Directive `ignoreTrailingSlash` ([#880](https://github.com/akka/akka-http/issues/880))
 * Prepared new seed templates for Akka HTTP apps (for both [Java DSL](https://github.com/akka/akka-http-java-seed.g8) and [Scala DSL](https://github.com/akka/akka-http-scala-seed.g8)) ([1137](https://github.com/akka/akka-http/issues/1137) & [1055](https://github.com/akka/akka-http/issues/1055))
 * Migrated to the new docs theme (same as Akka) ([#1129](https://github.com/akka/akka-http/issues/1129))
 * (ApiMayChange) `HttpApp#route` method was renamed to `routes` to highlight it is "all the routes" ([#953](https://github.com/akka/akka-http/issues/953))

#### akka-http2-support
 * Synthetic Remote-Address header setting is now honored in HTTP2 server blueprint ([#1088](https://github.com/akka/akka-http/issues/1088))

#### Bug Fixes

##### General
 * OSGi Import-Package ranges have been fixed to allow Akka 2.5.x ([#1097](https://github.com/akka/akka-http/issues/1097))

##### akka-http-core
 * Dates in RFC1123 format with single-digit-day are now properly parsed ([#1110](https://github.com/akka/akka-http/issues/1110))
 

## 10.0.6

See the [announcement](http://akka.io/news/2017/05/03/akka-http-10.0.6-released.html) and
closed tickets on the [10.0.6 milestone](https://github.com/akka/akka-http/milestone/23?closed=1).

10.0.6 is a security and maintenance release in the stable 10.0.x series of Akka HTTP.

@@@ warning

This release contains a fix for a serious security vulnerability that allows a remote attacker to shut down any Akka
HTTP application using the routing DSL. See the
@ref[details](security/2017-05-03-illegal-media-range-in-accept-header-causes-stackoverflowerror.md) for more information.
Please update as soon as possible.

@@@

### List of Changes

#### Improvements

##### akka-http-core
 * Make response parser more relaxed on accepting status line without reason message ([#981](https://github.com/akka/akka-http/issues/981))
 * Use media type parameters in content negotiation ([#963](https://github.com/akka/akka-http/issues/963))
 * Small performance improvements ([#999](https://github.com/akka/akka-http/issues/999), [#1032](https://github.com/akka/akka-http/issues/1032))
 * Added `HttpMessage.transformEntityDataBytes` ([#771](https://github.com/akka/akka-http/issues/771))
 * Allow binding server with HTTP/2 support via configuration flag with `Http().bindAndHandleAsync` ([#463](https://github.com/akka/akka-http/issues/463))

##### akka-http

 * Make marshaller composition more lazy to prevent redundant marshalling when using `Marshaller.oneOf` ([#1019](https://github.com/akka/akka-http/issues/1019))
 * Allow Java-implemented ContentTypeResolver ([#360](https://github.com/akka/akka-http/issues/360))
 * Java DSL routing `complete` now has override that takes`ResponseEntity` as a parameter instead of `RequestEntity` ([#982](https://github.com/akka/akka-http/issues/982))
 * Improved usage and documentation of Encoder / Decoder on the Scala and Java side ([#771](https://github.com/akka/akka-http/issues/771))

##### akka-http2-support

 * Refactoring: move handling of per-stream frames to dedicated state handlers ([#1064](https://github.com/akka/akka-http/issues/1064))

##### Documentation

 * Provide Decoding Response example for Java ([#760](https://github.com/akka/akka-http/issues/760))
 * Add Java example to extract header value with default value ([#639](https://github.com/akka/akka-http/issues/639))
 * Add HTTP custom method example ([#954](https://github.com/akka/akka-http/issues/954))
 * Smaller fixes and additions

##### Build + Infrastructure

 * Add OSGi to project in order to release each project with OSGi bundle headers ([#574](https://github.com/akka/akka-http/issues/574))
 * Rename root project to 'akka-http-root' ([#1030](https://github.com/akka/akka-http/issues/1030))

#### Bug Fixes

##### akka-http-core

 * Ignore unsupported `*/xyz` media types ([#1072](https://github.com/akka/akka-http/issues/1072))
 * Exclude port when rendering X-Forwarded-For and X-Real-Ip headers ([#440](https://github.com/akka/akka-http/issues/440))
 * Fix NPE when accessing static Java constant fields ([#936](https://github.com/akka/akka-http/issues/936))
 * Make sure pool log messages have "PoolGateway" set as logClass for easier filtering ([#1013](https://github.com/akka/akka-http/issues/1013))

##### akka-http

 * Move special non-2xx handling from RequestContextImpl to fromStatusCodeAndHeadersAndValue marshaller ([#1072](https://github.com/akka/akka-http/issues/1072))
 * Handle failure while parsing the URI in parameter extraction ([#1043](https://github.com/akka/akka-http/issues/1043))
 * Make `extractStrictEntity` provide strict entity for inner routes ([#961](https://github.com/akka/akka-http/issues/961))
 * Enable javadsl to unmarshal with default `ExecutionContext` ([#967](https://github.com/akka/akka-http/issues/967))
 * Smaller fixes for `HttpApp`

##### akka-http2-support

 * Fix memory leak in ALPN switcher ([#886](https://github.com/akka/akka-http/issues/886))

## 10.0.5

See the [announcement](http://akka.io/news/2017/03/17/akka-http-10.0.5-released.html) and
closed tickets on the [10.0.5 milestone](https://github.com/akka/akka-http/milestone/22?closed=1).

This is the fifth maintenance release of the Akka HTTP 10.0 series. It is primarily aimed at stability aligning the internals with the upcoming Akka 2.5 release. These steps are also the groundwork to enable Play to make use of Akka HTTP and the new Akka Streams materializer in the upcoming Play 2.6.

### List of Changes

#### Improvements

##### akka-http-core
 * New docs and API for registering custom headers with JavaDSL ([#761](https://github.com/akka/akka-http/issues/761))
 * Ssl-config upgraded to 0.2.2, allows disabling/changing hostname verification ([#943](https://github.com/akka/akka-http/issues/943))
 * Don’t depend on Akka internal APIs, become compatible with Akka 2.5 ([#877](https://github.com/akka/akka-http/issues/877))
 * Make default exception handler logging more informative ([#887](https://github.com/akka/akka-http/issues/887))

##### akka-http
 * Unmarshal.to now uses the materializer ExecutionContext if no other provided implicitly ([#947](https://github.com/akka/akka-http/pull/947))

#### Bug Fixes

##### akka-http-core
 * Prevent longer-than-needed lingering streams by fixing DelayCancellationStage ([#945](https://github.com/akka/akka-http/issues/945))

##### akka-http
 * Avoid redirect-loop when redirectToNoTrailingSlashIfPresent was used for root path ([#878](https://github.com/akka/akka-http/issues/878))

### Compatibility notes

This version of Akka HTTP must be used with Akka in version at-least 2.4.17, however it is also compatible with Akka 2.5, which has just released its Release Candidate 1.


## 10.0.4

See the [announcement](http://akka.io/news/2017/02/23/akka-http-10.0.4-released.html) and
closed tickets on the [10.0.4 milestone](https://github.com/akka/akka-http/milestone/21?closed=1).

This release contains mostly bug fixes and smaller improvements. We strongly recommend updating from 10.0.3 which
introduced a regression that an Akka HTTP server can leak memory over time which will lead to OOM eventually.
See [#851](https://github.com/akka/akka-http/issues/851) for more information.

### List of Changes

#### Improvements

##### akka-http-core
 * Http message and header parser now also accepts LF as end of line (as recommended in the spec) ([#106](https://github.com/akka/akka-http/issues/106))

##### akka-http
 * `HttpApp` now directly extends from Directives ([#875](https://github.com/akka/akka-http/issues/875))
 * Added `HttpApp.startServer(host, port)` for even simpler startup. ([#873](https://github.com/akka/akka-http/issues/873))

##### akka-http2-support
 * Multiplexer infrastructure was rewritten to support plugable `StreamPrioritizer` (not yet surfaced in user API) ([f06ab40](https://github.com/akka/akka-http/commit/f06ab40))

##### Documentation
 * New documentation page about how to deal with the client-side `max-open-requests` exception ([39f36dd](https://github.com/akka/akka-http/commit/39f36dd))
 * Lots of small cleanups and improvements

#### Bug Fixes

##### akka-http-core
 * Fix a regression introduced in 10.0.3 that might lead to memory leaking after a server connection has been closed. ([#851](https://github.com/akka/akka-http/issues/851))
 * Fix the infamous "Cannot push/pull twice" bug which occurred in relation with 100-Continue requests (like any kind
   of uploads of POST requests done with `curl`) ([#516](https://github.com/akka/akka-http/issues/516))

##### Build + Testing Infrastructure
 * Updated Akka dependency to Akka 2.4.17. ([#858](https://github.com/akka/akka-http/issues/858))
 * Use `.dilated` for tests for better stability. ([#194](https://github.com/akka/akka-http/issues/194))
 * Fix MiMa to actually check compatibility against the latest released versions. ([#870](https://github.com/akka/akka-http/issues/870))
 * Throughout the code base `@InternalApi`, `@ApiMayChange`, and `@DoNotInherit` annotations have been added
   to give hints about the stability of interfaces. ([#727](https://github.com/akka/akka-http/issues/727))

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

#### Bug Fixes

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
