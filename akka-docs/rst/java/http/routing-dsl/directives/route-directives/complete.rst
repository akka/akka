.. _-complete-java-:

complete
========

Description
-----------

Completes the request using the given argument(s).

``complete`` uses the given arguments to construct a ``Route`` which simply calls ``complete`` on the ``RequestContext``
with the respective ``HttpResponse`` instance.
Completing the request will send the response "back up" the route structure where all the logic runs that wrapping
directives have potentially chained into the :class:`RouteResult` future transformation chain.

Please note that the ``complete`` directive has multiple variants, like 

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/RouteDirectivesExamplesTest.java#complete
