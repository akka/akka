.. _-encodeResponseWith-java-:

encodeResponseWith
==================

Description
-----------

Encodes the response with the encoding that is requested by the client via the ``Accept-Encoding`` if it is among the provided encoders or rejects the request with an ``UnacceptedResponseEncodingRejection(supportedEncodings)``.

The response encoding is determined by the rules specified in RFC7231_.

If the ``Accept-Encoding`` header is missing then the response is encoded using the ``first`` encoder.

If the ``Accept-Encoding`` header is empty and ``NoCoding`` is part of the encoders then no
response encoding is used. Otherwise the request is rejected.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/CodingDirectivesExamplesTest.java#encodeResponseWith

.. _RFC7231: http://tools.ietf.org/html/rfc7231#section-5.3.4
