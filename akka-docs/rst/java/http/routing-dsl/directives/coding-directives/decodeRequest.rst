.. _-decodeRequest-java-:

decodeRequest
=============

Description
-----------

Decompresses the incoming request if it is ``gzip`` or ``deflate`` compressed. Uncompressed requests are passed through untouched. If the request encoded with another encoding the request is rejected with an ``UnsupportedRequestEncodingRejection``.

Example
-------
..TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
