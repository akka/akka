.. _-cancelRejection-java-:

cancelRejection
===============

Description
-----------

Adds a ``TransformationRejection`` cancelling all rejections equal to the
given one to the rejections potentially coming back from the inner route.

Read :ref:`rejections-java` to learn more about rejections.

For more advanced handling of rejections refer to the :ref:`-handleRejections-java-` directive
which provides a nicer DSL for building rejection handlers.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java#cancelRejection
