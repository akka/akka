.. _http-java:

Akka HTTP
=========

The Akka HTTP modules implement a full server- and client-side HTTP stack on top of *akka-actor* and *akka-stream*. It's
not a web-framework but rather a more general toolkit for providing and consuming HTTP-based services. While interaction
with a browser is of course also in scope it is not the primary focus of Akka HTTP.

Akka HTTP follows a rather open design and many times offers several different API levels for "doing the same thing".
You get to pick the API level of abstraction that is most suitable for your application.
This means that, if you have trouble achieving something using a high-level API, there's a good chance that you can get
it done with a low-level API, which offers more flexibility but might require you to write more application code.

Akka HTTP is structured into several modules:

akka-http-core
  A complete, mostly low-level, server- and client-side implementation of HTTP (incl. WebSockets).
  Includes a model of all things HTTP.

akka-http
  Higher-level functionality, like (un)marshalling, (de)compression as well as a powerful DSL
  for defining HTTP-based APIs on the server-side

akka-http-testkit
  A test harness and set of utilities for verifying server-side service implementations

akka-http-jackson
  Predefined glue-code for (de)serializing custom types from/to JSON with jackson_

.. toctree::
   :maxdepth: 2

   http-model
   server-side/low-level-server-side-api
   server-side/websocket-support
   routing-dsl/index
   client-side/index
   server-side-https-support
   configuration

.. _jackson: https://github.com/FasterXML/jackson