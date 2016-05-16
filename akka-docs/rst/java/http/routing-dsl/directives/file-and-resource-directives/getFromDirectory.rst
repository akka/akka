.. _-getFromDirectory-java-:

getFromDirectory
================

Description
-----------

Allows exposing a directory's files for GET requests for its contents.

The ``unmatchedPath`` (see :ref:`-extractUnmatchedPath-java-`) of the ``RequestContext`` is first transformed by
the given ``pathRewriter`` function, before being appended to the given directory name to build the final file name.

To serve a single file use :ref:`-getFromFile-java-`.
To serve browsable directory listings use :ref:`-getFromBrowseableDirectories-java-`.
To serve files from a classpath directory use :ref:`-getFromResourceDirectory-java-` instead.

Note that it's not required to wrap this directive with ``get`` as this directive will only respond to ``GET`` requests.

.. note::
  The file's contents will be read using an Akka Streams `Source` which *automatically uses
  a pre-configured dedicated blocking io dispatcher*, which separates the blocking file operations from the rest of the stream.

  Note also that thanks to using Akka Streams internally, the file will be served at the highest speed reachable by
  the client, and not faster – i.e. the file will *not* end up being loaded in full into memory before writing it to
  the client.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
