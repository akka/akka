.. _-post-java-:

post
====

Matches requests with HTTP method ``POST``.

Description
-----------

This directive filters the incoming request by its HTTP method. Only requests with
method ``POST`` are passed on to the inner route. All others are rejected with a
``MethodRejection``, which is translated into a ``405 Method Not Allowed`` response
by the default ``RejectionHandler``.


Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/MethodDirectivesExamplesTest.java#post
