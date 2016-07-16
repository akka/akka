.. _-decodeRequest-java-:

decodeRequest
=============

Description
-----------

Decompresses the incoming request if it is ``gzip`` or ``deflate`` compressed. Uncompressed requests are passed through untouched. If the request encoded with another encoding the request is rejected with an ``UnsupportedRequestEncodingRejection``.

.. note::
  Decompressed HTTP/1.0 requests are upgraded to HTTP/1.1 if server decides to change :ref:`HttpEntity-java` to ``HttpEntity.Chunked``.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/CodingDirectivesExamplesTest.java#decodeRequest
