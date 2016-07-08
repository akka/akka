.. _-redirect-java-:

redirect
========

Description
-----------
Completes the request with a redirection response to a given targer URI and of a given redirection type (status code).

``redirect`` is a convenience helper for completing the request with a redirection response.
It is equivalent to this snippet relying on the ``complete`` directive:


Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/RouteDirectivesExamplesTest.java#redirect
