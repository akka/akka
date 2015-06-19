.. _-requestEncodedWith-:

requestEncodedWith
==================

Passes the request to the inner route if the request is encoded with the argument encoding. Otherwise,
rejects the request with an ``UnacceptedRequestEncodingRejection(encoding)``.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/CodingDirectives.scala
   :snippet: requestEncodedWith

Description
-----------

This directive is the building block for ``decodeRequest`` to reject unsupported encodings.
