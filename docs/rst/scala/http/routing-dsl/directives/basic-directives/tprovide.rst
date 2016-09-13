.. _-tprovide-:

tprovide
========


Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: tprovide

Description
-----------

Provides a tuple of values to the inner route.

The ``tprovide`` directive is used as a building block for :ref:`Custom Directives` to provide data to the inner route.
To provide just one value use the :ref:`-provide-` directive. If you want to provide values calculated from the
``RequestContext`` use the :ref:`-textract-` directive instead.

See :ref:`ProvideDirectives` for an overview of similar directives.

See also :ref:`-provide-` for providing a single value.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: tprovide
