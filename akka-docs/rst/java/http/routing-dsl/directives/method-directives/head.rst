.. _-head-java-:

head
====
Matches requests with HTTP method ``HEAD``.

Description
-----------

This directive filters the incoming request by its HTTP method. Only requests with
method ``HEAD`` are passed on to the inner route. All others are rejected with a
``MethodRejection``, which is translated into a ``405 Method Not Allowed`` response
by the default ``RejectionHandler``.

.. note:: By default, akka-http handles HEAD-requests transparently by dispatching a GET-request to the handler and
   stripping of the result body. See the ``akka.http.server.transparent-head-requests`` setting for how to disable
   this behavior.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/MethodDirectivesExamplesTest.java#head
