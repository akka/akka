.. _-decodeRequest-:

decodeRequest
=============

Tries to decode the request with the specified ``Decoder`` or rejects the request with an
``UnacceptedRequestEncodingRejection(supportedEncoding)``.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/server/directives/CodingDirectives.scala
   :snippet: decodeRequest

Description
-----------

The ``decodeRequest`` directive is the building block for the ``decompressRequest`` directive.

``decodeRequest`` and ``decompressRequest`` are related like this::

    decompressRequest(Gzip)          = decodeRequest(Gzip)
    decompressRequest(a, b, c)       = decodeRequest(a) | decodeRequest(b) | decodeRequest(c)
    decompressRequest()              = decodeRequest(Gzip) | decodeRequest(Deflate) | decodeRequest(NoEncoding)

Example
-------

.. includecode2:: ../../../code/docs/http/server/directives/CodingDirectivesExamplesSpec.scala
   :snippet: decodeRequest
