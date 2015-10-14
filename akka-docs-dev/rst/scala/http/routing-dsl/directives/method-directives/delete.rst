.. _-delete-:

delete
======

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/MethodDirectives.scala
   :snippet: delete


Description
-----------
Matches requests with HTTP method ``DELETE``.

This directive filters an incoming request by its HTTP method. Only requests with
method ``DELETE`` are passed on to the inner route. All others are rejected with a
``MethodRejection``, which is translated into a ``405 Method Not Allowed`` response
by the default :ref:`RejectionHandler <The RejectionHandler>`.


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MethodDirectivesExamplesSpec.scala
  :snippet: delete-method
