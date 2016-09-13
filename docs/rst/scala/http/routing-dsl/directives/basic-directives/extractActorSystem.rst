.. _-extractActorSystem-:

extractActorSystem
==================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: extractActorSystem

Description
-----------

Extracts the ``ActorSystem`` from the ``RequestContext``, which can be useful when the external API
in your route needs one.

.. warning::

  This is only supported when the available Materializer is an ActorMaterializer.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: extractActorSystem-example
