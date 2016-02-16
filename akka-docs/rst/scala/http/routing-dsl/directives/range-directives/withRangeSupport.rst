.. _-withRangeSupport-:

withRangeSupport
================

Signature
---------

::

    def withRangeSupport(): Directive0
    def withRangeSupport(rangeCountLimit: Int, rangeCoalescingThreshold:Long): Directive0

The signature shown is simplified, the real signature uses magnets. [1]_

.. [1] See `The Magnet Pattern`_ for an explanation of magnet-based overloading.
.. _`The Magnet Pattern`: http://spray.io/blog/2012-12-13-the-magnet-pattern/


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

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/RangeDirectivesExamplesSpec.scala
   :snippet: withRangeSupport
