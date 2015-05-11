.. _http-high-level-server-side-api:

High-level Server-Side API
==========================

In addition to the :ref:`http-low-level-server-side-api` Akka HTTP provides a very flexible "Routing DSL" for elegantly
defining RESTful web services. It picks up where the low-level API leaves off and offers much of the higher-level
functionality of typical web servers or frameworks, like deconstruction of URIs, content negotiation or
static content serving.

.. toctree::
   :maxdepth: 1

   overview
   routes
   directives/index
   rejections
   exception-handling
   path-matchers
   case-class-extraction
   testkit
   https-support
   websocket-support


Minimal Example
---------------

This is a complete, very basic Akka HTTP application relying on the Routing DSL:

.. includecode2:: ../../code/docs/http/scaladsl/HttpServerExampleSpec.scala
   :snippet: minimal-routing-example

It starts an HTTP Server on localhost and replies to GET requests to ``/hello`` with a simple response.


.. _Long Example:

Longer Example
--------------

The following is an Akka HTTP route definition that tries to show off a few features. The resulting service does
not really do anything useful but its definition should give you a feel for what an actual API definition with
the :ref:`http-high-level-server-side-api` will look like:

.. includecode2:: ../../code/docs/http/scaladsl/HttpServerExampleSpec.scala
   :snippet: long-routing-example