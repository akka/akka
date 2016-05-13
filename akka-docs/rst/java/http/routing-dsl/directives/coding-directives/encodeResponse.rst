.. _-encodeResponse-java-:

encodeResponse
==============

Description
-----------

Encodes the response with the encoding that is requested by the client via the ``Accept-Encoding`` header or rejects the request with an ``UnacceptedResponseEncodingRejection(supportedEncodings)``.

The response encoding is determined by the rules specified in RFC7231_.

If the ``Accept-Encoding`` header is missing or empty or specifies an encoding other than identity, gzip or deflate then no encoding is used.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.

.. _RFC7231: http://tools.ietf.org/html/rfc7231#section-5.3.4
