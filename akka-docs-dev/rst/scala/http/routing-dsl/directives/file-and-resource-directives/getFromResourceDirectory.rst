.. _-getFromResourceDirectory-:

getFromResourceDirectory
========================

Completes GET requests with the content of the given classpath resource directory.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/FileAndResourceDirectives.scala
   :snippet: getFromResourceDirectory

Description
-----------

The actual I/O operation is running detached in a `Future`, so it doesn't block the current thread (but potentially
some other thread !). If the file cannot be found or read the request is rejected.

To serve a single resource use ``getFromResource``, instead. To server files from a filesystem directory use
``getFromDirectory`` instead.

Note that it's not required to wrap this directive with ``get`` as this directive will only respond to ``GET`` requests.