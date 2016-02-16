.. _-getFromBrowseableDirectories-:

getFromBrowseableDirectories
============================

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/FileAndResourceDirectives.scala
   :snippet: getFromBrowseableDirectories

Description
-----------

The ``getFromBrowseableDirectories`` is a combination of serving files from the specified directories
(like ``getFromDirectory``) and listing a browseable directory with ``listDirectoryContents``.

Nesting this directive beneath ``get`` is not necessary as this directive will only respond to ``GET`` requests.

Use ``getFromBrowseableDirectory`` to serve only one directory.

Use ``getFromDirectory`` if directory browsing isn't required.

For more details refer to :ref:`-getFromBrowseableDirectory-`.

Example
-------

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/FileAndResourceDirectivesExamplesSpec.scala
   :snippet: getFromBrowseableDirectories-examples
