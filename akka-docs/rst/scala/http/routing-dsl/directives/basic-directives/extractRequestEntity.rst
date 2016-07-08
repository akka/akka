.. _-extractRequestEntity-:

extractRequestEntity
====================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: extractRequestEntity

Description
-----------

Extracts the ``RequestEntity`` from the :class:`RequestContext`.

The directive returns a ``RequestEntity`` without unmarshalling the request. To extract domain entity,
:ref:`-entity-` should be used.


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: extractRequestEntity-example
