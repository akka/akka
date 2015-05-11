.. _-extractHost-:

extractHost
===========

Extracts the hostname part of the Host header value in the request.


Signature
---------

.. includecode2:: /../../akka-http-scala/src/main/scala/akka/http/scaladsl/server/directives/HostDirectives.scala
   :snippet: extractHost


Description
-----------

Extract the hostname part of the ``Host`` request header and expose it as a ``String`` extraction
to its inner route.


Example
-------

... includecode2:: ../../../../code/docs/http/scaladsl/server/directives/HostExamplesSpec.scala
   :snippet: extract-hostname