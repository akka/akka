.. _-textract-:

textract
========

Calculates a tuple of values from the request context and provides them to the inner route.

Signature
---------

.. includecode2:: /../../akka-http-scala/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: textract

Description
-----------

The ``textract`` directive is used as a building block for :ref:`Custom Directives` to extract data from the
``RequestContext`` and provide it to the inner route. To extract just one value use the :ref:`-extract-` directive. To
provide a constant value independent of the ``RequestContext`` use the :ref:`-tprovide-` directive instead.

See :ref:`ProvideDirectives` for an overview of similar directives.


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: textract
