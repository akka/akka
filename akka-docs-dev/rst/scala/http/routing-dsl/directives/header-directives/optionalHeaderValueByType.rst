.. _-optionalHeaderValueByType-:

optionalHeaderValueByType
=========================

Optionally extracts the value of the HTTP request header of the given type.

Signature
---------

::

    def optionalHeaderValueByType[T <: HttpHeader: ClassTag](): Directive1[Option[T]]

The signature shown is simplified, the real signature uses magnets. [1]_

.. [1] See `The Magnet Pattern`_ for an explanation of magnet-based overloading.
.. _`The Magnet Pattern`: /blog/2012-12-13-the-magnet-pattern/

Description
-----------

The ``optionalHeaderValueByType`` directive is similar to the ``headerValueByType`` directive but always extracts
an ``Option`` value instead of rejecting the request if no matching header could be found.

Example
-------

... includecode2:: ../../../../code/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala
   :snippet: optionalHeaderValueByType-0
