.. _-requestEncodedWith-:

requestEncodedWith
==================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/CodingDirectives.scala
   :snippet: requestEncodedWith

Description
-----------

Passes the request to the inner route if the request is encoded with the argument encoding. Otherwise, rejects the request with an ``UnacceptedRequestEncodingRejection(encoding)``.

This directive is the `building block`_ for ``decodeRequest`` to reject unsupported encodings.

.. _`building block`: @github@/akka-http/src/main/scala/akka/http/scaladsl/server/directives/CodingDirectives.scala
