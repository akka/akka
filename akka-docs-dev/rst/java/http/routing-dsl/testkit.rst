.. _http-testkit-java:

Route Testkit
=============

akka-http has a testkit that provides a convenient way of testing your routes with JUnit. It allows
running requests against a route (without hitting the network) and provides means to assert against
response properties in a compact way.

To use the testkit you need to take these steps:

* add a dependency to the ``akka-http-testkit-experimental`` module
* derive the test class from ``JUnitRouteTest``
* wrap the route under test with ``RouteTest.testRoute`` to create a ``TestRoute``
* run requests against the route using ``TestRoute.run(request)`` which will return
  a ``TestResponse``
* use the methods of ``TestResponse`` to assert on properties of the response

Example
-------

To see the testkit in action consider the following simple calculator app service:

.. includecode:: ../../code/docs/http/javadsl/server/testkit/MyAppService.java
  :include: simple-app

The app extends from ``HttpApp`` which brings all of the directives into scope. Method ``createRoute``
needs to be implemented to return the complete route of the app.

Here's how you would test that service:

.. includecode:: ../../code/docs/http/javadsl/server/testkit/TestkitExampleTest.java
  :include: simple-app-testing


Writing Asserting against the HttpResponse
------------------------------------------

The testkit supports a fluent DSL to write compact assertions on the response by chaining assertions
using "dot-syntax". To simplify working with streamed responses the entity of the response is first "strictified", i.e.
entity data is collected into a single ``ByteString`` and provided the entity is supplied as an ``HttpEntityStrict``. This
allows to write several assertions against the same entity data which wouldn't (necessarily) be possible for the
streamed version.

All of the defined assertions provide HTTP specific error messages aiding in diagnosing problems.

Currently, these methods are defined on ``TestResponse`` to assert on the response:

=================================================================== =======================================================================
Assertion                                        Description
=================================================================== =======================================================================
``assertStatusCode(int expectedCode)``                              Asserts that the numeric response status code equals the expected one
``assertStatusCode(StatusCode expectedCode)``                       Asserts that the response ``StatusCode`` equals the expected one
``assertMediaType(String expectedType)``                            Asserts that the media type part of the response's content type matches
                                                                    the given String
``assertMediaType(MediaType expectedType)``                         Asserts that the media type part of the response's content type matches
                                                                    the given ``MediaType``
``assertEntity(String expectedStringContent)``                      Asserts that the entity data interpreted as UTF8 equals the expected
                                                                    String
``assertEntityBytes(ByteString expectedBytes)``                     Asserts that the entity data bytes equal the expected ones
``assertEntityAs(Unmarshaller<T> unmarshaller, expectedValue: T)``  Asserts that the entity data if unmarshalled with the given marshaller
                                                                    equals the given value
``assertHeaderExists(HttpHeader expectedHeader)``                   Asserts that the response contains an HttpHeader instance equal to the
                                                                    expected one
``assertHeaderKindExists(String expectedHeaderName)``               Asserts that the response contains a header with the expected name
``assertHeader(String name, String expectedValue)``                 Asserts that the response contains a header with the given name and
                                                                    value.
=================================================================== =======================================================================

It's, of course, possible to use any other means of writing assertions by inspecting the properties the response
manually. As written above, ``TestResponse.entity`` and ``TestResponse.response`` return strict versions of the
entity data.

Supporting Custom Test Frameworks
---------------------------------

Adding support for a custom test framework is achieved by creating new superclass analogous to
``JUnitRouteTest`` for writing tests with the custom test framwork deriving from ``akka.http.javadsl.testkit.RouteTest``
and implementing its abstract methods. This will allow users of the test framework to use ``testRoute`` and
to write assertions using the assertion methods defined on ``TestResponse``.