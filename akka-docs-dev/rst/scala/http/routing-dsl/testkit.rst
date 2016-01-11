Route TestKit
=============

One of Akka HTTP's design goals is good testability of the created services.
For services built with the Routing DSL Akka HTTP provides a dedicated testkit that makes efficient testing of
route logic easy and convenient. This "route test DSL" is made available with the *akka-http-testkit* module.


Usage
-----

Here is an example of what a simple test with the routing testkit might look like (using the built-in support for
scalatest_):

.. includecode2:: ../../code/docs/http/scaladsl/server/FullTestKitExampleSpec.scala
   :snippet: source-quote

The basic structure of a test built with the testkit is this (expression placeholder in all-caps)::

    REQUEST ~> ROUTE ~> check {
      ASSERTIONS
    }

In this template *REQUEST* is an expression evaluating to an ``HttpRequest`` instance.
In most cases your test will, in one way or another, extend from ``RouteTest`` which itself mixes in the
``akka.http.scaladsl.client.RequestBuilding`` trait, which gives you a concise and convenient way of constructing
test requests. [1]_

*ROUTE* is an expression evaluating to a :ref:`Route <Routes>`. You can specify one inline or simply refer to the
route structure defined in your service.

The final element of the ``~>`` chain is a ``check`` call, which takes a block of assertions as parameter. In this block
you define your requirements onto the result produced by your route after having processed the given request. Typically
you use one of the defined "inspectors" to retrieve a particular element of the routes response and express assertions
against it using the test DSL provided by your test framework. For example, with scalatest_, in order to verify that
your route responds to the request with a status 200 response, you'd use the ``status`` inspector and express an
assertion like this::

    status shouldEqual 200

The following inspectors are defined:

================================================ =======================================================================
Inspector                                        Description
================================================ =======================================================================
``charset: HttpCharset``                         Identical to ``contentType.charset``
``chunks: Seq[HttpEntity.ChunkStreamPart]``      Returns the entity chunks produced by the route. If the entity is not
                                                 ``chunked`` returns ``Nil``.
``closingExtension: String``                     Returns chunk extensions the route produced with its last response
                                                 chunk. If the response entity is unchunked returns the empty string.
``contentType: ContentType``                     Identical to ``responseEntity.contentType``
``definedCharset: Option[HttpCharset]``          Identical to ``contentType.definedCharset``
``entityAs[T :FromEntityUnmarshaller]: T``       Unmarshals the response entity using the in-scope
                                                 ``FromEntityUnmarshaller`` for the given type. Any errors in the
                                                 process trigger a test failure.
``handled: Boolean``                             Indicates whether the route produced an ``HttpResponse`` for the
                                                 request. If the route rejected the request ``handled`` evaluates to
                                                 ``false``.
``header(name: String): Option[HttpHeader]``     Returns the response header with the given name or ``None`` if no such
                                                 header is present in the response.
``header[T <: HttpHeader]: Option[T]``           Identical to ``response.header[T]``
``headers: Seq[HttpHeader]``                     Identical to ``response.headers``
``mediaType: MediaType``                         Identical to ``contentType.mediaType``
``rejection: Rejection``                         The rejection produced by the route. If the route did not produce
                                                 exactly one rejection a test failure is triggered.
``rejections: Seq[Rejection]``                   The rejections produced by the route. If the route did not reject the
                                                 request a test failure is triggered.
``response: HttpResponse``                       The ``HttpResponse`` returned by the route. If the route did not return
                                                 an ``HttpResponse`` instance (e.g. because it rejected the request) a
                                                 test failure is triggered.
``responseAs[T: FromResponseUnmarshaller]: T``   Unmarshals the response entity using the in-scope
                                                 ``FromResponseUnmarshaller`` for the given type. Any errors in the
                                                 process trigger a test failure.
``responseEntity: HttpEntity``                   Returns the response entity.
``status: StatusCode``                           Identical to ``response.status``
``trailer: Seq[HttpHeader]``                     Returns the list of trailer headers the route produced with its last
                                                 chunk. If the response entity is unchunked returns ``Nil``.
================================================ =======================================================================

.. [1] If the request URI is relative it will be made absolute using an implicitly available instance of
        ``DefaultHostInfo`` whose value is "http://example.com" by default. This mirrors the behavior of akka-http-core
        which always produces absolute URIs for incoming request based on the request URI and the ``Host``-header of
        the request. You can customize this behavior by bringing a custom instance of ``DefaultHostInfo`` into scope.


Sealing Routes
--------------

The section above describes how to test a "regular" branch of your route structure, which reacts to incoming requests
with HTTP response parts or rejections. Sometimes, however, you will want to verify that your service also translates
:ref:`rejections-scala` to HTTP responses in the way you expect.

You do this by wrapping your route with the ``akka.http.scaladsl.server.Route.seal``.
The ``seal`` wrapper applies the logic of the in-scope :ref:`ExceptionHandler <exception-handling-scala>` and
:ref:`RejectionHandler <The RejectionHandler>` to all exceptions and rejections coming back from the route,
and translates them to the respective ``HttpResponse``.


Examples
--------

A great pool of examples are the tests for all the predefined directives in Akka HTTP.
They can be found here__.

__ @github@/akka-http-tests/src/test/scala/akka/http/scaladsl/server/directives/

.. _scalatest: http://www.scalatest.org