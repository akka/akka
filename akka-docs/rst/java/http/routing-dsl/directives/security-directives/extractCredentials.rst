.. _-extractCredentials-java-:

extractCredentials
==================

Description
-----------

Extracts the potentially present ``HttpCredentials`` provided with the request's ``Authorization`` header,
which can be then used to implement some custom authentication or authorization logic.

See :ref:`credentials-and-timing-attacks-java` for details about verifying the secret.

Example
-------
TODO: Add example snippet.
.. 
.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/SecurityDirectivesExamplesSpec.scala
   :snippet: 0extractCredentials
