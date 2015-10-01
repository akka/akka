.. _-getFromDirectory-:

getFromDirectory
================

Completes GET requests with the content of a file underneath the given directory.

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/FileAndResourceDirectives.scala
   :snippet: getFromDirectory

Description
-----------

The ``unmatchedPath`` of the ``RequestContext`` is first transformed by the given ``pathRewriter`` function before being
appended to the given directory name to build the final file name.

The actual I/O operation is running detached in a `Future`, so it doesn't block the current thread. If the file cannot
be read the route rejects the request.

To serve a single file use ``getFromFile``. To serve browsable directory listings use ``getFromBrowseableDirectories``.
To serve files from a classpath directory use ``getFromResourceDirectory`` instead.

Note that it's not required to wrap this directive with ``get`` as this directive will only respond to ``GET`` requests.

Example
-------

...