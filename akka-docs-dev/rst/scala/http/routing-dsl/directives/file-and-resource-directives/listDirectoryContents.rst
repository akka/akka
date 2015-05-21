.. _-listDirectoryContents-:

listDirectoryContents
=====================

Completes GET requests with a unified listing of the contents of all given directories. The actual rendering of the
directory contents is performed by the in-scope `Marshaller[DirectoryListing]`.

Signature
---------

.. includecode2:: /../../akka-http-scala/src/main/scala/akka/http/scaladsl/server/directives/FileAndResourceDirectives.scala
   :snippet: listDirectoryContents

Description
-----------

The ``listDirectoryContents`` directive renders a response only for directories. To just serve files use
``getFromDirectory``. To serve files and provide a browseable directory listing use ``getFromBrowsableDirectories``
instead.

The rendering can be overridden by providing a custom ``Marshaller[DirectoryListing]``.

Note that it's not required to wrap this directive with ``get`` as this directive will only respond to ``GET`` requests.