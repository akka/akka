.. _-mapInnerRoute-java-:

mapInnerRoute
=============

Description
-----------
Changes the execution model of the inner route by wrapping it with arbitrary logic.

The ``mapInnerRoute`` directive is used as a building block for :ref:`Custom Directives-java` to replace the inner route
with any other route. Usually, the returned route wraps the original one with custom execution logic.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java#mapInnerRoute
