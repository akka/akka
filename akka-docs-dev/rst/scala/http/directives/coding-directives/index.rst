.. _CodingDirectives:

CodingDirectives
================

.. toctree::
   :maxdepth: 1

   compressResponse
   compressResponseIfRequested
   decodeRequest
   decompressRequest
   encodeResponse
   requestEncodedWith
   responseEncodingAccepted

.. _WhenToUseWhichCompressResponseDirective:

When to use which compression directive?
----------------------------------------

There are three different directives for performing response compressing with slightly different behavior:

:ref:`-encodeResponse-`
  Always compresses the response with the one given encoding, rejects the request with an
  ``UnacceptedResponseEncodingRejection`` if the client doesn't accept the given encoding. The other
  compression directives are built upon this one. See its description for an overview how they
  relate exactly.

:ref:`-compressResponse-`
  Uses the first of a given number of encodings that the client accepts. If none are accepted the request
  is rejected.

:ref:`-compressResponseIfRequested-`
  Only compresses the response when specifically requested by the
  ``Accept-Encoding`` request header (i.e. the default is "no compression").

See the individual directives for more detailed usage examples.

.. _WhenToUseWhichDecompressRequestDirective:

When to use which decompression directive?
------------------------------------------

There are two different directives for performing request decompressing with slightly different behavior:

:ref:`-decodeRequest-`
  Attempts to decompress the request using **the one given decoder**, rejects the request with an
  ``UnsupportedRequestEncodingRejection`` if the request is not encoded with the given encoder.

:ref:`-decompressRequest-`
  Decompresses the request if it is encoded with **one of the given encoders**.
  If the request's encoding doesn't match one of the given encoders it is rejected.


Combining compression and decompression
---------------------------------------

As with all Spray directives, the above single directives can be combined
using ``&`` to produce compound directives that will decompress requests and
compress responses in whatever combination required. Some examples:

.. includecode2:: /../spray-routing-tests/src/test/scala/spray/routing/EncodingDirectivesSpec.scala
   :snippet: decompress-compress-combination-example
