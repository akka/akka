.. _-encodeResponse-:

encodeResponse
==============

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/CodingDirectives.scala
   :snippet: encodeResponse

Description
-----------

Encodes the response with the encoding that is requested by the client via the ``Accept-Encoding`` header or rejects the request with an ``UnacceptedResponseEncodingRejection(supportedEncodings)``.

The response encoding is determined by the rules specified in RFC7231_.

If the ``Accept-Encoding`` header is missing or empty or specifies an encoding other than identity, gzip or deflate then no encoding is used.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/CodingDirectivesExamplesSpec.scala
   :snippet: "encodeResponse"

.. _RFC7231: http://tools.ietf.org/html/rfc7231#section-5.3.4
