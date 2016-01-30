.. _-getFromResource-:

getFromResource
===============

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/FileAndResourceDirectives.scala
   :snippet: getFromResource

Description
-----------

Completes GET requests with the content of the given classpath resource.

For details refer to :ref:`-getFromFile-` which works the same way but obtaining the file from the filesystem
instead of the applications classpath.

Note that it's not required to wrap this directive with ``get`` as this directive will only respond to ``GET`` requests.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/FileAndResourceDirectivesExamplesSpec.scala
   :snippet: getFromResource-examples