.. _-provide-:

provide
=======

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: provide

Description
-----------
Provides a constant value to the inner route.

The `provide` directive is used as a building block for :ref:`Custom Directives` to provide a single value to the
inner route. To provide several values  use the :ref:`-tprovide-` directive.

See :ref:`ProvideDirectives` for an overview of similar directives.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: 0provide
