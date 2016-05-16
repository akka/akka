.. _-headerValueByName-java-:

headerValueByName
=================

Description
-----------
Extracts the value of the HTTP request header with the given name.

If no header with a matching name is found the request is rejected with a ``MissingHeaderRejection``.

If the header is expected to be missing in some cases or to customize
handling when the header is missing use the :ref:`-optionalHeaderValueByName-java-` directive instead.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
