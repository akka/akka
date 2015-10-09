.. _-withLog-:

withLog
=======

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/BasicDirectives.scala
   :snippet: withLog

Description
-----------

Allows running an inner route using an alternative :class:`LoggingAdapter` in place of the default one.

The logging adapter can be extracted in an inner route using :ref:`-extractLog-` directly,
or used by directives which internally extract the materializer without sufracing this fact in the API.


Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/BasicDirectivesExamplesSpec.scala
   :snippet: 0withLog
