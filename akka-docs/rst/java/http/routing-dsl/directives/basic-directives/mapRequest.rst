.. _-mapRequest-java-:

mapRequest
==========

Description
-----------
Transforms the request before it is handled by the inner route.

The ``mapRequest`` directive is used as a building block for :ref:`Custom Directives-java` to transform a request before it
is handled by the inner route. Changing the ``request.uri`` parameter has no effect on path matching in the inner route
because the unmatched path is a separate field of the ``RequestContext`` value which is passed into routes. To change
the unmatched path or other fields of the ``RequestContext`` use the :ref:`-mapRequestContext-java-` directive.

See :ref:`Request Transforming Directives-java` for an overview of similar directives.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/BasicDirectivesExamplesTest.java#mapRequest
