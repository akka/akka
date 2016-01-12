.. _-extractUnmatchedPath-:

extractUnmatchedPath
====================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: extractUnmatchedPath

Description
-----------
Extracts the unmatched path from the request context.

The ``extractUnmatchedPath`` directive extracts the remaining path that was not yet matched by any of the :ref:`PathDirectives`
(or any custom ones that change the unmatched path field of the request context). You can use it for building directives
that handle complete suffixes of paths (like the ``getFromDirectory`` directives and similar ones).

Use ``mapUnmatchedPath`` to change the value of the unmatched path.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: extractUnmatchedPath-example
