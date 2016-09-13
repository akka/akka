.. _-extractLog-java-:

extractLog
==========

Description
-----------

Extracts a :class:`LoggingAdapter` from the request context which can be used for logging inside the route.

The ``extractLog`` directive is used for providing logging to routes, such that they don't have to depend on
closing over a logger provided in the class body.

See :ref:`-extract-java-` and :ref:`ProvideDirectives-java` for an overview of similar directives.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java#extractLog
