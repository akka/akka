.. _-get-java-:

get
===
Matches requests with HTTP method ``GET``.

Description
-----------

This directive filters the incoming request by its HTTP method. Only requests with
method ``GET`` are passed on to the inner route. All others are rejected with a
``MethodRejection``, which is translated into a ``405 Method Not Allowed`` response
by the default ``RejectionHandler``.


Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/MethodDirectivesExamplesTest.java#get
