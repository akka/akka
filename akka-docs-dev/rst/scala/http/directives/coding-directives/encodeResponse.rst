.. _-encodeResponse-:

encodeResponse
==============

Tries to encode the response with the specified ``Encoder`` or rejects the request with an
``UnacceptedResponseEncodingRejection(supportedEncodings)``.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/server/directives/CodingDirectives.scala
   :snippet: encodeResponse

Description
-----------

The directive automatically applies the ``autoChunkFileBytes`` directive as well to avoid having to load
an entire file into JVM heap.

The parameter to the directive is either just an ``Encoder`` or all of an ``Encoder``, a threshold, and a
chunk size to configure the automatically applied ``autoChunkFileBytes`` directive.

The ``encodeResponse`` directive is the building block for the ``compressResponse`` and
``compressResponseIfRequested`` directives.

``encodeResponse``, ``compressResponse``, and ``compressResponseIfRequested`` are related like this::

    compressResponse(Gzip)          = encodeResponse(Gzip)
    compressResponse(a, b, c)       = encodeResponse(a) | encodeResponse(b) | encodeResponse(c)
    compressResponse()              = encodeResponse(Gzip) | encodeResponse(Deflate) | encodeResponse(NoEncoding)
    compressResponseIfRequested()   = encodeResponse(NoEncoding) | encodeResponse(Gzip) | encodeResponse(Deflate)

Example
-------

.. includecode2:: ../../../code/docs/http/server/directives/CodingDirectivesExamplesSpec.scala
   :snippet: encodeResponse
