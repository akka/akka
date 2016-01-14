.. _-mapUnmatchedPath-:

mapUnmatchedPath
================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: mapUnmatchedPath

Description
-----------
Transforms the unmatchedPath field of the request context for inner routes.

The ``mapUnmatchedPath`` directive is used as a building block for writing :ref:`Custom Directives`. You can use it
for implementing custom path matching directives.

Use ``extractUnmatchedPath`` for extracting the current value of the unmatched path.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: mapUnmatchedPath-example
