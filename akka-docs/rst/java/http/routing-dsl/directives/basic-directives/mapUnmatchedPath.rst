.. _-mapUnmatchedPath-java-:

mapUnmatchedPath
================

Description
-----------
Transforms the unmatchedPath field of the request context for inner routes.

The ``mapUnmatchedPath`` directive is used as a building block for writing :ref:`Custom Directives-java`. You can use it
for implementing custom path matching directives.

Use ``extractUnmatchedPath`` for extracting the current value of the unmatched path.

Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: mapUnmatchedPath-example
