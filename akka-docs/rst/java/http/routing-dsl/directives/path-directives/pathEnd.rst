.. _-pathEnd-java-:

pathEnd
=======

Signature
---------
TODO: Add example snippet.
.. 
.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/PathDirectives.scala
   :snippet: pathEnd


Description
-----------
Only passes the request to its inner route if the unmatched path of the ``RequestContext`` is empty, i.e. the request
path has been fully matched by a higher-level :ref:`-path-java-` or :ref:`-pathPrefix-java-` directive.


This directive is a simple alias for ``rawPathPrefix(PathEnd)`` and is mostly used on an
inner-level to discriminate "path already fully matched" from other alternatives (see the example below).


Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala
   :snippet: pathEnd-
