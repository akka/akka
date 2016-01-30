.. _-mapRequestContext-:

mapRequestContext
=================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: mapRequestContext

Description
-----------
Transforms the ``RequestContext`` before it is passed to the inner route.

The ``mapRequestContext`` directive is used as a building block for :ref:`Custom Directives` to transform
the request context before it is passed to the inner route. To change only the request value itself the
:ref:`-mapRequest-` directive can be used instead.

See :ref:`Request Transforming Directives` for an overview of similar directives.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: mapRequestContext
