# Release Notes

## 10.0.5

See the [announcement](http://akka.io/news/2017/03/17/akka-http-10.0.5-released.html) and
closed tickets on the [10.0.5 milestone](https://github.com/akka/akka-http/milestone/22?closed=1).

This is the fifth maintenance release of the Akka HTTP 10.0 series. It is primarily aimed at stability aligning the internals with the upcoming Akka 2.5 release. These steps are also the groundwork to enable Play to make use of Akka HTTP and the new Akka Streams materializer in the upcoming Play 2.6.

### List of Changes

#### Improvements:

##### akka-http-core
 * New docs and API for registering custom headers with JavaDSL ([#761](https://github.com/akka/akka-http/issues/761))
 * Ssl-config upgraded to 0.2.2, allows disabling/changing hostname verification ([#943](https://github.com/akka/akka-http/issues/943))
 * Don’t depend on Akka internal APIs, become compatible with Akka 2.5 ([#877](https://github.com/akka/akka-http/issues/877))
 * Make default exception handler logging more informative ([#887](https://github.com/akka/akka-http/issues/887))

##### akka-http
 * Unmarshal.to now uses the materializer ExecutionContext if no other provided implicitly ([#947](https://github.com/akka/akka-http/pull/947))

#### Bug fixes:

##### akka-http-core
 * Prevent longer-than-needed lingering streams by fixing DelayCancellationStage ([#945](https://github.com/akka/akka-http/issues/945))

##### akka-http
 * Avoid redirect-loop when redirectToNoTrailingSlashIfPresent was used for root path ([#878](https://github.com/akka/akka-http/issues/878))

### Compatibility notes

This version of Akka HTTP must be used with Akka in version at-least 2.4.17, however it is also compatible with Akka 2.5, which has just released its Release Candidate 1. 


## 10.0.4

See the [announcement](http://akka.io/news/2017/01/26/akka-http-10.0.4-released.html) and
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

#### Bug fixes

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