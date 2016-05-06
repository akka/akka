.. _-decodeRequestWith-java-:

decodeRequestWith
=================

Description
-----------

Decodes the incoming request if it is encoded with one of the given encoders. If the request encoding doesn't match one of the given encoders the request is rejected with an ``UnsupportedRequestEncodingRejection``. If no decoders are given the default encoders (``Gzip``, ``Deflate``, ``NoCoding``) are used.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
