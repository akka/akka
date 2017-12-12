# Route TestKit

One of Akka HTTP's design goals is good testability of the created services.
For services built with the Routing DSL Akka HTTP provides a dedicated testkit that makes efficient testing of
route logic easy and convenient. This "route test DSL" is made available with the *akka-http-testkit* module.

To use it include the following dependency:

@@dependency [sbt,Gradle,Maven] {
  group="com.typesafe.akka"
  artifact="akka-http-testkit_$scala.binary.version$"
  version="$project.version$"
}

## Usage

@@@ div { .group-scala }

Here is an example of what a simple test with the routing testkit might look like using the built-in support for
[scalatest](http://www.scalatest.org) and [specs2](http://etorreborre.github.io/specs2/):

ScalaTest
:  @@snip [FullTestKitExampleSpec.scala]($test$/scala/docs/http/scaladsl/server/FullTestKitExampleSpec.scala) { #source-quote }

specs2
:  @@snip [FullSpecs2TestKitExampleSpec.scala]($test$/scala/docs/http/scaladsl/server/FullSpecs2TestKitExampleSpec.scala) { #source-quote }

The basic structure of a test built with the testkit is this (expression placeholder in all-caps):

```
REQUEST ~> ROUTE ~> check {
  ASSERTIONS
}
```

In this template *REQUEST* is an expression evaluating to an @unidoc[HttpRequest] instance.
In most cases your test will, in one way or another, extend from @unidoc[RouteTest] which itself mixes in the
`akka.http.scaladsl.client.RequestBuilding` trait, which gives you a concise and convenient way of constructing
test requests. <a id="^1" href="#1">[1]</a>

*ROUTE* is an expression evaluating to a @ref[Route](routes.md). You can specify one inline or simply refer to the
route structure defined in your service.

The final element of the `~>` chain is a `check` call, which takes a block of assertions as parameter. In this block
you define your requirements onto the result produced by your route after having processed the given request. Typically
you use one of the defined "inspectors" to retrieve a particular element of the routes response and express assertions
against it using the test DSL provided by your test framework. For example, with [scalatest](http://www.scalatest.org), in order to verify that
your route responds to the request with a status 200 response, you'd use the `status` inspector and express an
assertion like this:

```scala
status shouldEqual 200
```

The following inspectors are defined:

### Table of Inspectors

|Inspector                                    | Description                                                                                                                                                         |
|---------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
|`charset: HttpCharset`                       | Identical to `contentType.charset`                                                                                                                                  |
|`chunks: Seq[HttpEntity.ChunkStreamPart]`    | Returns the entity chunks produced by the route. If the entity is not `chunked` returns `Nil`.                                                                      |
|`closingExtension: String`                   | Returns chunk extensions the route produced with its last response chunk. If the response entity is unchunked returns the empty string.                             |
|`contentType: ContentType`                   | Identical to `responseEntity.contentType`                                                                                                                           |
|`definedCharset: Option[HttpCharset]`        | Identical to `contentType.definedCharset`                                                                                                                           |
|`entityAs[T :FromEntityUnmarshaller]: T`     | Unmarshals the response entity using the in-scope `FromEntityUnmarshaller` for the given type. Any errors in the process trigger a test failure.                    |
|`handled: Boolean`                           | Indicates whether the route produced an @unidoc[HttpResponse] for the request. If the route rejected the request `handled` evaluates to `false`.                           |
|`header(name: String): Option[HttpHeader]`   | Returns the response header with the given name or `None` if no such header is present in the response.                                                             |
|`header[T <: HttpHeader]: Option[T]`         | Identical to `response.header[T]`                                                                                                                                   |
|`headers: Seq[HttpHeader]`                   | Identical to `response.headers`                                                                                                                                     |
|`mediaType: MediaType`                       | Identical to `contentType.mediaType`                                                                                                                                |
|`rejection: Rejection`                       | The rejection produced by the route. If the route did not produce exactly one rejection a test failure is triggered.                                                |
|`rejections: Seq[Rejection]`                 | The rejections produced by the route. If the route did not reject the request a test failure is triggered.                                                          |
|`response: HttpResponse`                     | The @unidoc[HttpResponse] returned by the route. If the route did not return an @unidoc[HttpResponse] instance (e.g. because it rejected the request) a test failure is triggered.|
|`responseAs[T: FromResponseUnmarshaller]: T` | Unmarshals the response entity using the in-scope `FromResponseUnmarshaller` for the given type. Any errors in the process trigger a test failure.                  |
|`responseEntity: HttpEntity`                 | Returns the response entity.                                                                                                                                        |
|`status: StatusCode`                         | Identical to `response.status`                                                                                                                                      |
|`trailer: Seq[HttpHeader]`                   | Returns the list of trailer headers the route produced with its last chunk. If the response entity is unchunked returns `Nil`.                                      |

> <a id="1" href="#^1">[1]</a> If the request URI is relative it will be made absolute using an implicitly available instance of
`DefaultHostInfo` whose value is "[http://example.com](http://example.com)" by default. This mirrors the behavior of akka-http-core
which always produces absolute URIs for incoming request based on the request URI and the `Host`-header of
the request. You can customize this behavior by bringing a custom instance of `DefaultHostInfo` into scope.

@@@

@@@ div { .group-java }

To use the testkit you need to take these steps:

 * add a dependency to the `akka-http-testkit` module
 * derive the test class from `JUnitRouteTest`
 * wrap the route under test with `RouteTest.testRoute` to create a `TestRoute`
 * run requests against the route using `TestRoute.run(request)` which will return
a `TestResponse`
 * use the methods of `TestResponse` to assert on properties of the response

## Example

To see the testkit in action consider the following simple calculator app service:

Java
:   @@snip [MyAppService.java]($test$/java/docs/http/javadsl/server/testkit/MyAppService.java) { #simple-app }

`MyAppService` extends from `AllDirectives` which brings all of the directives into scope. We define a method called `createRoute`
that provides the routes to serve to `bindAndHandle`.

Here's how you would test that service:

Java
:   @@snip [TestkitExampleTest.java]($test$/java/docs/http/javadsl/server/testkit/TestkitExampleTest.java) { #simple-app-testing }

## Writing Asserting against the HttpResponse

The testkit supports a fluent DSL to write compact assertions on the response by chaining assertions
using "dot-syntax". To simplify working with streamed responses the entity of the response is first "strictified", i.e.
entity data is collected into a single @unidoc[ByteString] and provided the entity is supplied as an `HttpEntityStrict`. This
allows to write several assertions against the same entity data which wouldn't (necessarily) be possible for the
streamed version.

All of the defined assertions provide HTTP specific error messages aiding in diagnosing problems.

Currently, these methods are defined on `TestResponse` to assert on the response:

|Inspector                                                           | Description                                                                                                                         |
|--------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------|
|`assertStatusCode(int expectedCode)`                                | Asserts that the numeric response status code equals the expected one                                                               |
|`assertStatusCode(StatusCode expectedCode)`                         | Asserts that the response @unidoc[StatusCode] equals the expected one                                                                      |
|`assertMediaType(String expectedType)`                              | Asserts that the media type part of the response's content type matches the given String                                            |
|`assertMediaType(MediaType expectedType)`                           | Asserts that the media type part of the response's content type matches the given @unidoc[MediaType]                                       |
|`assertEntity(String expectedStringContent)`                        | Asserts that the entity data interpreted as UTF8 equals the expected String                                                         |
|`assertEntityBytes(ByteString expectedBytes)`                       | Asserts that the entity data bytes equal the expected ones                                                                          |
|`assertEntityAs(Unmarshaller<T> unmarshaller, expectedValue: T)`    | Asserts that the entity data if unmarshalled with the given marshaller equals the given value                                       |
|`assertHeaderExists(HttpHeader expectedHeader)`                     | Asserts that the response contains an HttpHeader instance equal to the expected one                                                 |
|`assertHeaderKindExists(String expectedHeaderName)`                 | Asserts that the response contains a header with the expected name                                                                  |
|`assertHeader(String name, String expectedValue)`                   | Asserts that the response contains a header with the given name and value.                                                          |

It's, of course, possible to use any other means of writing assertions by inspecting the properties the response
manually. As written above, `TestResponse.entity` and `TestResponse.response` return strict versions of the
entity data.

## Supporting Custom Test Frameworks

Adding support for a custom test framework is achieved by creating new superclass analogous to
`JUnitRouteTest` for writing tests with the custom test framework deriving from `akka.http.javadsl.testkit.RouteTest`
and implementing its abstract methods. This will allow users of the test framework to use `testRoute` and
to write assertions using the assertion methods defined on `TestResponse`.


@@@

## Testing sealed Routes

The section above describes how to test a "regular" branch of your route structure, which reacts to incoming requests
with HTTP response parts or rejections. Sometimes, however, you will want to verify that your service also translates
@ref[Rejections](rejections.md) to HTTP responses in the way you expect.

You do this by calling the `Route.seal()` method. The `Route.seal()` method applies the logic of the @scala[in-scope]
@ref[RejectionHandler](rejections.md#the-rejectionhandler) and @ref[RejectionHandler](rejections.md#the-rejectionhandler)
@java[passed as method arguments] to all exceptions and rejections coming back from the
route, and translates them to the respective @unidoc[HttpResponse].

Note that explicit call on the `Route.seal` method is needed in test code, but in your application code it is not necessary.
As described in @ref[Sealing a Route](routes.md#sealing-a-route), your application code only needs to bring
implicit rejection and exception handlers in scope.

## Testing Route fragments

Since the testkit is request-based, you cannot test requests that are illegal or impossible in HTTP. One
such instance is testing a route that begins with the `pathEnd` directive, such as `routeFragment` here:

Scala
:   @@snip [TestKitFragmentSpec.scala]($test$/scala/docs/http/scaladsl/server/TestKitFragmentSpec.scala) { #fragment }

Java
:   @@snip [MyAppFragment.java]($test$/java/docs/http/javadsl/server/testkit/MyAppFragment.java) { #fragment}

You might create a route such as this to be able to compose it into another route such as:

Scala
:   @@snip [FragmentExample.scala]($test$/scala/docs/http/scaladsl/server/FragmentExample.scala) { #source-quote }

Java
:   @@snip [MyAppFragment.java]($test$/java/docs/http/javadsl/server/testkit/MyAppFragment.java) { #source-quote }


However, it is impossible to unit test this Route directly using testkit, since it is impossible to create an
empty HTTP request. To test this type of route, embed it in a synthetic route in your test, such as `testRoute` in the example above.

This is what the full working test looks like:

Scala
:   @@snip [TestKitFragmentSpec.scala]($test$/scala/docs/http/scaladsl/server/TestKitFragmentSpec.scala) { #source-quote }

Java
:   @@snip [TestKitFragmentTest.java]($test$/java/docs/http/javadsl/server/testkit/TestKitFragmentTest.java) { #source-quote }

## Accounting for Slow Test Systems

The timeouts you consciously defined on your lightning fast development environment might be too tight for your, most
probably, high-loaded Continuous Integration server, invariably causing spurious failures. To account for such
situations, timeout durations can be scaled by a given factor on such environments. Check the
@scala[@extref[Akka Docs](akka-docs:scala/testing.html#accounting-for-slow-test-systems)]@java[@extref[Akka Docs](akka-docs:java/testing.html#accounting-for-slow-test-systems)]
for further information.


## Increase Timeout

The default timeout when testing your routes using the testkit is @scala[1 second]@java[3 seconds] second. Sometimes, though, this might not be enough.
In order to extend this default timeout, to say 5 seconds, just add the following implicit in scope:

Scala
:   @@snip [TestKitFragmentSpec.scala]($test$/scala/docs/http/scaladsl/server/TestKitFragmentSpec.scala) { #timeout-setting }

Java
:   @@snip [WithTimeoutTest.java]($test$/java/docs/http/javadsl/server/testkit/WithTimeoutTest.java) { #timeout-setting }

Remember to configure the timeout using `dilated` if you want to account for slow test systems.

## Examples

A great pool of examples are the tests for all the predefined directives in Akka HTTP.
They can be found @scala[@github[here](/akka-http-tests/src/test/scala/akka/http/scaladsl/server/directives/)]@java[@github[here](/akka-http-tests/src/test/java/akka/http/javadsl/server/directives/)].
