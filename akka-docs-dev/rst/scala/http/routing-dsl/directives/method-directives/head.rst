.. _-head-:

head
====

Matches requests with HTTP method ``HEAD``.

Signature
---------

.. includecode2:: /../../akka-http-scala/src/main/scala/akka/http/scaladsl/server/directives/MethodDirectives.scala
   :snippet: head

Description
-----------

This directive filters the incoming request by its HTTP method. Only requests with
method ``HEAD`` are passed on to the inner route. All others are rejected with a
``MethodRejection``, which is translated into a ``405 Method Not Allowed`` response
by the default :ref:`RejectionHandler <The RejectionHandler>`.

.. note:: By default, spray-can handles HEAD-requests transparently by dispatching a GET-request to the handler and
   stripping of the result body. See the ``spray.can.server.transparent-head-requests`` setting for how to disable
   this behavior.

Example
-------

... includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MethodDirectivesExamplesSpec.scala
  :snippet: head-method
