.. _-extractCredentials-:

extractCredentials
==================

Extracts the potentially present ``HttpCredentials`` provided with the request's ``Authorization`` header.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala
   :snippet: extractCredentials

Description
-----------

Extracts the potentially present ``HttpCredentials`` provided with the request's ``Authorization`` header,
which can be then used to implement some custom authentication or authorization logic.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/SecurityDirectivesExamplesSpec.scala
   :snippet: 0extractCredentials
