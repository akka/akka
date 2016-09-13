.. _-extractMaterializer-java-:

extractMaterializer
===================

Description
-----------

Extracts the ``Materializer`` from the ``RequestContext``, which can be useful when you want to run an
Akka Stream directly in your route.

See also :ref:`-withMaterializer-java-` to see how to customise the used materializer for specific inner routes.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java#extractMaterializer
