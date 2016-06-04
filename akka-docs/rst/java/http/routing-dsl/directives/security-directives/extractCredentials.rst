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

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/SecurityDirectivesExamplesTest.java#extractCredentials
