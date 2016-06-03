.. _-mapRequestContext-java-:

mapRequestContext
=================

Description
-----------
Transforms the ``RequestContext`` before it is passed to the inner route.

The ``mapRequestContext`` directive is used as a building block for :ref:`Custom Directives-java` to transform
the request context before it is passed to the inner route. To change only the request value itself the
:ref:`-mapRequest-java-` directive can be used instead.

See :ref:`Request Transforming Directives-java` for an overview of similar directives.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java#mapRequestContext
