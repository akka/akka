.. _http-client-side:

Consuming HTTP-based Services (Client-Side)
===========================================

All client-side functionality of Akka HTTP, for consuming HTTP-based services offered by other systems, is currently
provided by the ``akka-http-core`` module.

Depending your application's specific needs you can choose from three different API levels:

:ref:`ConnectionLevelApi`
  for full-control over when HTTP connections are opened/closed and how requests are scheduled across them

:ref:`HostLevelApi`
  for letting Akka HTTP manage a connection-pool to *one specific* host/port endpoint

:ref:`RequestLevelApi`
  for letting Akka HTTP perform all connection management

You can interact with different API levels at the same time and, independently of which API level you choose,
Akka HTTP will happily handle many thousand concurrent connections to a single or many different hosts.


.. toctree::
   :maxdepth: 2

   connection-level
   host-level
   request-level
   https-support
   websocket-support