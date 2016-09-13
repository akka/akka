.. _-withoutSizeLimit-:

withoutSizeLimit
================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/MiscDirectives.scala
   :snippet: withoutSizeLimit

Description
-----------
Skips request entity size verification.

The whole mechanism of entity size checking is intended to prevent certain Denial-of-Service attacks.
So suggested setup is to have ``akka.http.parsing.max-content-length`` relatively low and use ``withoutSizeLimit``
directive just for endpoints for which size verification should not be performed.

See also :ref:`-withSizeLimit-` for setting request entity size limit.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala
  :snippet: withoutSizeLimit-example
