.. _-getFromBrowseableDirectories-:

getFromBrowseableDirectories
============================

Serves the content of the given directories as a file system browser, i.e. files are sent and directories
served as browsable listings.

Signature
---------

.. includecode2:: /../../akka-http-scala/src/main/scala/akka/http/scaladsl/server/directives/FileAndResourceDirectives.scala
   :snippet: getFromBrowseableDirectories

Description
-----------

The ``getFromBrowseableDirectories`` is a combination of serving files from the specified directories (like
``getFromDirectory``) and listing a browseable directory with ``listDirectoryContents``. Nesting this directive beneath
``get`` is not necessary as this directive will only respond to ``GET`` requests.

Use ``getFromBrowseableDirectory`` to serve only one directory. Use ``getFromDirectory`` if directory browsing isn't
required.
