Introduction
============

The Akka HTTP modules implement a full server- and client-side HTTP stack on top of *akka-actor* and *akka-stream*. It's
not a web-framework but rather a more general toolkit for providing and consuming HTTP-based services. While interaction
with a browser is of course also in scope it is not the primary focus of Akka HTTP.

Akka HTTP follows a rather open design and many times offers several different API levels for "doing the same thing".
You get to pick the API level of abstraction that is most suitable for your application.
This means that, if you have trouble achieving something using a high-level API, there's a good chance that you can get
it done with a low-level API, which offers more flexibility but might require you do write more application code.

Akka HTTP is structured into several modules:

akka-http-core
  A complete, mostly low-level, server- and client-side implementation of HTTP (incl. WebSockets)

akka-http
  Higher-level functionality, like (un)marshalling, (de)compression as well as a powerful DSL
  for defining HTTP-based APIs on the server-side

akka-http-testkit
  A test harness and set of utilities for verifying server-side service implementations

akka-http-spray-json
  Predefined glue-code for (de)serializing custom types from/to JSON with spray-json_

akka-http-xml
  Predefined glue-code for (de)serializing custom types from/to XML with scala-xml_

.. _spray-json: https://github.com/spray/spray-json
.. _scala-xml: https://github.com/scala/scala-xml