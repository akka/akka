.. _-responseEncodingAccepted-:

responseEncodingAccepted
========================

Passes the request to the inner route if the request accepts the argument encoding. Otherwise,
rejects the request with an ``UnacceptedResponseEncodingRejection(encoding)``.

Signature
---------

.. includecode2:: /../../akka-http-scala/src/main/scala/akka/http/scaladsl/server/directives/CodingDirectives.scala
   :snippet: responseEncodingAccepted

Description
-----------

This directive is the building block for ``encodeResponse`` to reject unsupported encodings.
