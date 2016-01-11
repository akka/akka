.. _-get-:

get
===

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/MethodDirectives.scala
   :snippet: get

Description
-----------
Matches requests with HTTP method ``GET``.

This directive filters the incoming request by its HTTP method. Only requests with
method ``GET`` are passed on to the inner route. All others are rejected with a
``MethodRejection``, which is translated into a ``405 Method Not Allowed`` response
by the default :ref:`RejectionHandler <The RejectionHandler>`.


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MethodDirectivesExamplesSpec.scala
  :snippet: get-method
