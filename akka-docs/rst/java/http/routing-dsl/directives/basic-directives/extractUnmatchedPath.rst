.. _-extractUnmatchedPath-java-:

extractUnmatchedPath
====================

Description
-----------
Extracts the unmatched path from the request context.

The ``extractUnmatchedPath`` directive extracts the remaining path that was not yet matched by any of the :ref:`PathDirectives-java`
(or any custom ones that change the unmatched path field of the request context). You can use it for building directives
that handle complete suffixes of paths (like the ``getFromDirectory`` directives and similar ones).

Use ``mapUnmatchedPath`` to change the value of the unmatched path.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
