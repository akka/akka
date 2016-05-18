.. _-withRangeSupport-java-:

withRangeSupport
================

Description
-----------
Transforms the response from its inner route into a ``206 Partial Content``
response if the client requested only part of the resource with a ``Range`` header.

Augments responses to ``GET`` requests with an ``Accept-Ranges: bytes`` header and converts them into partial responses
if the request contains a valid ``Range`` request header. The requested byte-ranges are coalesced (merged) if they
lie closer together than the specified ``rangeCoalescingThreshold`` argument.

In order to prevent the server from becoming overloaded with trying to prepare ``multipart/byteranges`` responses for
high numbers of potentially very small ranges the directive rejects requests requesting more than ``rangeCountLimit``
ranges with a ``TooManyRangesRejection``.
Requests with unsatisfiable ranges are rejected with an ``UnsatisfiableRangeRejection``.

The ``withRangeSupport()`` form (without parameters) uses the ``range-coalescing-threshold`` and ``range-count-limit``
settings from the ``akka.http.routing`` configuration.

This directive is transparent to non-``GET`` requests.

See also: https://tools.ietf.org/html/rfc7233


Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
