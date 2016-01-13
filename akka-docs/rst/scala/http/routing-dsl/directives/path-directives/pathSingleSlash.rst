.. _-pathSingleSlash-:

pathSingleSlash
===============

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/PathDirectives.scala
   :snippet: pathSingleSlash


Description
-----------
Only passes the request to its inner route if the unmatched path of the ``RequestContext``
contains exactly one single slash.

This directive is a simple alias for ``pathPrefix(PathEnd)`` and is mostly used for matching requests to the root URI
(``/``) on an inner-level to discriminate "all path segments matched" from other alternatives (see the example below).


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala
   :snippet: pathSingleSlash-