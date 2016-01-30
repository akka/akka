.. _-extractCredentials-:

extractCredentials
==================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/SecurityDirectives.scala
   :snippet: extractCredentials

Description
-----------

Extracts the potentially present ``HttpCredentials`` provided with the request's ``Authorization`` header,
which can be then used to implement some custom authentication or authorization logic.

See :ref:`credentials-and-timing-attacks-scala` for details about verifying the secret.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/SecurityDirectivesExamplesSpec.scala
   :snippet: 0extractCredentials
