.. _-getFromResourceDirectory-java-:

getFromResourceDirectory
========================

Description
-----------

Completes ``GET`` requests with the content of the given classpath resource directory.

For details refer to :ref:`-getFromDirectory-java-` which works the same way but obtaining the file from the filesystem
instead of the applications classpath.

Note that it's not required to wrap this directive with ``get`` as this directive will only respond to ``GET`` requests.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/FileAndResourceDirectivesExamplesTest.java#getFromResourceDirectory
