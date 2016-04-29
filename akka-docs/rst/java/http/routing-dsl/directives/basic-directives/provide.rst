.. _-provide-java-:

provide
=======

Signature
---------
TODO: Add example snippet.
.. 
.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: provide

Description
-----------
Provides a constant value to the inner route.

The `provide` directive is used as a building block for :ref:`Custom Directives` to provide a single value to the
inner route. To provide several values  use the :ref:`-tprovide-java-` directive.

See :ref:`ProvideDirectives-java` for an overview of similar directives.

Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: 0provide
