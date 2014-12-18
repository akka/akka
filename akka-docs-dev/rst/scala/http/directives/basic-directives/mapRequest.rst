.. _-mapRequest-:

mapRequest
==========

Transforms the request before it is handled by the inner route.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/server/directives/BasicDirectives.scala
   :snippet: mapRequest

Description
-----------

The ``mapRequest`` directive is used as a building block for :ref:`Custom Directives` to transform a request before it
is handled by the inner route. Changing the ``request.uri`` parameter has no effect on path matching in the inner route
because the unmatched path is a separate field of the ``RequestContext`` value which is passed into routes. To change
the unmatched path or other fields of the ``RequestContext`` use the :ref:`-mapRequestContext-` directive.

See :ref:`Request Transforming Directives` for an overview of similar directives.

Example
-------

.. includecode2:: ../../../code/docs/http/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: 0mapRequest
