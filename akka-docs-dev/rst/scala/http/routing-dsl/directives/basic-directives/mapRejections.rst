.. _-mapRejections-:

mapRejections
=============

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: mapRejections

Description
-----------

**Low level directive** – unless you're sure you need to be working on this low-level you might instead
want to try the :ref:`-handleRejections-` directive which provides a nicer DSL for building rejection handlers.

The ``mapRejections`` directive is used as a building block for :ref:`Custom Directives` to transform a list
of rejections from the inner route to a new list of rejections.

See :ref:`Response Transforming Directives` for similar directives.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: mapRejections
