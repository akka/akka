.. _-listDirectoryContents-java-:

listDirectoryContents
=====================

Description
-----------

Completes GET requests with a unified listing of the contents of all given directories. The actual rendering of the
directory contents is performed by the in-scope ``Marshaller[DirectoryListing]``.

To just serve files use :ref:`-getFromDirectory-java-`.

To serve files and provide a browseable directory listing use :ref:`-getFromBrowseableDirectories-java-` instead.

The rendering can be overridden by providing a custom ``Marshaller[DirectoryListing]``, you can read more about it in
:ref:`-getFromDirectory-java-` 's documentation.

Note that it's not required to wrap this directive with ``get`` as this directive will only respond to ``GET`` requests.

Example
-------

.. includecode:: ../../../../code/docs/http/javadsl/server/directives/FileAndResourceDirectivesExamplesTest.java#listDirectoryContents
