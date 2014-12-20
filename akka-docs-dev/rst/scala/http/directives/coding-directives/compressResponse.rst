.. _-compressResponse-:

compressResponse
================

Uses the first of a given number of encodings that the client accepts. If none are accepted the request
is rejected with an ``UnacceptedResponseEncodingRejection``.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/server/directives/CodingDirectives.scala
   :snippet: compressResponse

Description
-----------

The ``compressResponse`` directive allows to specify zero to three encoders to try in the specified order.
If none are specified the tried list is ``Gzip``, ``Deflate``, and then ``NoEncoding``.

The ``compressResponse()`` directive (without an explicit list of encoders given) will therefore behave as follows:

========================================= ===============================
``Accept-Encoding`` header                resulting response
========================================= ===============================
``Accept-Encoding: gzip``                 compressed with ``Gzip``
``Accept-Encoding: deflate``              compressed with ``Deflate``
``Accept-Encoding: deflate, gzip``        compressed with ``Gzip``
``Accept-Encoding: identity``             uncompressed
no ``Accept-Encoding`` header present     compressed with ``Gzip``
========================================= ===============================

For an overview of the different ``compressResponse`` directives see :ref:`WhenToUseWhichCompressResponseDirective`.

Example
-------

This example shows the behavior of ``compressResponse`` without any encoders specified:

.. includecode2:: ../../../code/docs/http/server/directives/CodingDirectivesExamplesSpec.scala
   :snippet: compressResponse-0

This example shows the behaviour of ``compressResponse(Gzip)``:

.. includecode2:: ../../../code/docs/http/server/directives/CodingDirectivesExamplesSpec.scala
   :snippet: compressResponse-1
