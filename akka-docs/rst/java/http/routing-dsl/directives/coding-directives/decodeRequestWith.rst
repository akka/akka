.. _-decodeRequestWith-java-:

decodeRequestWith
=================

Description
-----------

Decodes the incoming request if it is encoded with one of the given encoders. If the request encoding doesn't match one of the given encoders the request is rejected with an ``UnsupportedRequestEncodingRejection``. If no decoders are given the default encoders (``Gzip``, ``Deflate``, ``NoCoding``) are used.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/CodingDirectivesExamplesTest.java#decodeRequestWith
