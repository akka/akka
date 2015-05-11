.. _-extractClientIP-:

extractClientIP
===============

Provides the value of ``X-Forwarded-For``, ``Remote-Address``, or ``X-Real-IP`` headers as an instance of
``HttpIp``.

Signature
---------

.. includecode2:: /../../akka-http-scala/src/main/scala/akka/http/scaladsl/server/directives/MiscDirectives.scala
   :snippet: extractClientIP

Description
-----------

spray-can and spray-servlet adds the ``Remote-Address`` header to every request automatically if the respective
setting ``spray.can.server.remote-address-header`` or ``spray.servlet.remote-address-header`` is set to ``on``.
Per default it is set to ``off``.

Example
-------

... includecode2:: ../../../../code/docs/http/scaladsl/server/directives/MiscDirectivesExamplesSpec.scala
  :snippet: extractClientIP-example

