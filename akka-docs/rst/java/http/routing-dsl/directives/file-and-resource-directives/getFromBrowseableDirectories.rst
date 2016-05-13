.. _-getFromBrowseableDirectories-java-:

getFromBrowseableDirectories
============================

Description
-----------

The ``getFromBrowseableDirectories`` is a combination of serving files from the specified directories
(like ``getFromDirectory``) and listing a browseable directory with ``listDirectoryContents``.

Nesting this directive beneath ``get`` is not necessary as this directive will only respond to ``GET`` requests.

Use ``getFromBrowseableDirectory`` to serve only one directory.

Use ``getFromDirectory`` if directory browsing isn't required.

For more details refer to :ref:`-getFromBrowseableDirectory-java-`.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
