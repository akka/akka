.. _directives-java:

Directives
==========

A directive is a wrapper for a route or a list of alternative routes that adds one or more of the following
functionality to its nested route(s):

* it filters the request and lets only matching requests pass (e.g. the `get` directive lets only GET-requests pass)
* it modifies the request or the ``RequestContext`` (e.g. the `path` directives filters on the unmatched path and then
  passes an updated ``RequestContext`` unmatched path)
* it modifies the response coming out of the nested route

akka-http provides a set of predefined directives for various tasks. You can access them by either extending from
``akka.http.javadsl.server.AllDirectives`` or by importing them statically with
``import static akka.http.javadsl.server.Directives.*;``.

These classes of directives are currently defined:

BasicDirectives
  Contains methods to create routes that complete with a static values or allow specifying :ref:`handlers-java` to
  process a request.

CacheConditionDirectives
  Contains a single directive ``conditional`` that wraps its inner route with support for Conditional Requests as defined
  by `RFC 7234`_.

CodingDirectives
  Contains directives to decode compressed requests and encode responses.

CookieDirectives
  Contains a single directive ``setCookie`` to aid adding a cookie to a response.

ExecutionDirectives
  Contains directives to deal with exceptions that occurred during routing.

FileAndResourceDirectives
  Contains directives to serve resources from files on the file system or from the classpath.

HostDirectives
  Contains directives to filter on the ``Host`` header of the incoming request.

MethodDirectives
  Contains directives to filter on the HTTP method of the incoming request.

MiscDirectives
  Contains directives that validate a request by user-defined logic.

:ref:`path-directives-java`
  Contains directives to match and filter on the URI path of the incoming request.

RangeDirectives
  Contains a single directive ``withRangeSupport`` that adds support for retrieving partial responses.

SchemeDirectives
  Contains a single directive ``scheme`` to filter requests based on the URI scheme (http vs. https).

WebsocketDirectives
  Contains directives to support answering Websocket requests.

TODO this page should be rewritten as the corresponding Scala page

.. toctree::
  :maxdepth: 1

  path-directives
  method-directives/index
  host-directives/index

.. _`RFC 7234`: http://tools.ietf.org/html/rfc7234