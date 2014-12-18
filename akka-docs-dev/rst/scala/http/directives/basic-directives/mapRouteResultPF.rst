.. _-mapRouteResponsePF-:

mapRouteResponsePF
==================

Changes the message the inner route sends to the responder.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/server/directives/BasicDirectives.scala
   :snippet: mapRouteResponsePF

Description
-----------

The ``mapRouteResponsePF`` directive is used as a building block for :ref:`Custom Directives` to transform what
the inner route sends to the responder (see :ref:`The Responder Chain`). It's similar to the :ref:`-mapRouteResult-`
directive but allows to specify a partial function that doesn't have to handle all the incoming response messages.

See :ref:`Result Transformation Directives` for similar directives.

Example
-------

.. includecode2:: ../../../code/docs/http/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: mapRouteResultPF
