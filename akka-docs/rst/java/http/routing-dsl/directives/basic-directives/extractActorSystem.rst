.. _-extractActorSystem-java-:

extractActorSystem
==================

Description
-----------

Extracts the ``ActorSystem`` from the ``RequestContext``, which can be useful when the external API
in your route needs one.

.. warning::

  This is only supported when the available Materializer is an ActorMaterializer.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java#extractActorSystem
