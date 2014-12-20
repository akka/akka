.. _http-scala:

Akka HTTP
=========

The purpose of the Akka HTTP layer is to expose Actors to the web via HTTP and
to enable them to consume HTTP services as a client. It is not an HTTP
framework, it is an Actor-based toolkit for interacting with web services and
clients. This toolkit is structured into several layers:

  * ``akka-http-core``: A complete implementation of the HTTP standard, both as
    client and server.

  * ``akka-http``: A convenient and powerful routing DSL for expressing web
    services.

  * ``akka-http-testkit``: A test harness and set of utilities for verifying
    your web service implementations.

Additionally there are several integration modules with bindings for different
serialization and deserialization schemes for HTTP entities.

.. toctree::
   :maxdepth: 2

   index-client-server
   index-routing
   index-testkit
