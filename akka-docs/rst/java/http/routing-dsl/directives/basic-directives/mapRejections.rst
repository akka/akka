.. _-mapRejections-java-:

mapRejections
=============

Description
-----------

**Low level directive** – unless you're sure you need to be working on this low-level you might instead
want to try the :ref:`-handleRejections-java-` directive which provides a nicer DSL for building rejection handlers.

The ``mapRejections`` directive is used as a building block for :ref:`Custom Directives-java` to transform a list
of rejections from the inner route to a new list of rejections.

See :ref:`Response Transforming Directives-java` for similar directives.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java#mapRejections
