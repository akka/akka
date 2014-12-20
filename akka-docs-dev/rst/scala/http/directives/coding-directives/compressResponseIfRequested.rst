.. _-compressResponseIfRequested-:

compressResponseIfRequested
===========================

Only compresses the response when specifically requested by the ``Accept-Encoding`` request header
(i.e. the default is "no compression").

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/server/directives/CodingDirectives.scala
   :snippet: compressResponseIfRequested

Description
-----------

The ``compressResponseIfRequested`` directive is an alias for ``compressResponse(NoEncoding, Gzip, Deflate)`` and will
behave as follows:

========================================= ===============================
``Accept-Encoding`` header                resulting response
========================================= ===============================
``Accept-Encoding: gzip``                 compressed with ``Gzip``
``Accept-Encoding: deflate``              compressed with ``Deflate``
``Accept-Encoding: deflate, gzip``        compressed with ``Gzip``
``Accept-Encoding: identity``             uncompressed
no ``Accept-Encoding`` header present     uncompressed
========================================= ===============================

For an overview of the different ``compressResponse`` directives see :ref:`WhenToUseWhichCompressResponseDirective`.

Example
-------

.. includecode2:: ../../../code/docs/http/server/directives/CodingDirectivesExamplesSpec.scala
   :snippet: compressResponseIfRequested
