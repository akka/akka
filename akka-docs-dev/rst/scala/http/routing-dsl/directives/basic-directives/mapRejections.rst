.. _-mapRejections-:

mapRejections
=============

Transforms the list of rejections the inner route produced.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: mapRejections

Description
-----------

The ``mapRejections`` directive is used as a building block for :ref:`Custom Directives` to transform a list
of rejections from the inner route to a new list of rejections.

See :ref:`Response Transforming Directives` for similar directives.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: mapRejections
