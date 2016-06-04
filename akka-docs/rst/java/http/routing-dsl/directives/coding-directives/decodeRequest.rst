.. _-decodeRequest-java-:

decodeRequest
=============

Description
-----------

Decompresses the incoming request if it is ``gzip`` or ``deflate`` compressed. Uncompressed requests are passed through untouched. If the request encoded with another encoding the request is rejected with an ``UnsupportedRequestEncodingRejection``.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/CodingDirectivesExamplesTest.java#decodeRequest
