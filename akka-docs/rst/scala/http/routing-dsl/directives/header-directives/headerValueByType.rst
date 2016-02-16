.. _-headerValueByType-:

headerValueByType
=================

Signature
---------

::

    def headerValueByType[T <: HttpHeader: ClassTag](): Directive1[T]

The signature shown is simplified, the real signature uses magnets. [1]_

.. [1] See `The Magnet Pattern`_ for an explanation of magnet-based overloading.
.. _`The Magnet Pattern`: http://spray.io/blog/2012-12-13-the-magnet-pattern/

Description
-----------
Traverses the list of request headers and extracts the first header of the given type.

The ``headerValueByType`` directive finds a header of the given type in the list of request header. If no header of
the given type is found the request is rejected with a ``MissingHeaderRejection``.

If the header is expected to be missing in some cases or to customize handling when the header
is missing use the :ref:`-optionalHeaderValueByType-` directive instead.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/HeaderDirectivesExamplesSpec.scala
   :snippet: headerValueByType-0
