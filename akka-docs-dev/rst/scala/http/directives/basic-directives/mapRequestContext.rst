.. _-mapRequestContext-:

mapRequestContext
=================

Transforms the ``RequestContext`` before it is passed to the inner route.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/server/directives/BasicDirectives.scala
   :snippet: mapRequestContext

Description
-----------

The ``mapRequestContext`` directive is used as a building block for :ref:`Custom Directives` to transform
the request context before it is passed to the inner route. To change only the request value itself the
:ref:`-mapRequest-` directive can be used instead.

See :ref:`Request Transforming Directives` for an overview of similar directives.

Example
-------

.. includecode2:: ../../../code/docs/http/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: mapRequestContext
