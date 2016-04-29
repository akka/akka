.. _-decodeRequest-java-:

decodeRequest
=============

Description
-----------

Decompresses the incoming request if it is ``gzip`` or ``deflate`` compressed. Uncompressed requests are passed through untouched. If the request encoded with another encoding the request is rejected with an ``UnsupportedRequestEncodingRejection``.

Example
-------

..TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/CodingDirectivesExamplesSpec.scala
   :snippet: "decodeRequest"
