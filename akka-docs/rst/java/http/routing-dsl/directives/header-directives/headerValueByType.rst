.. _-headerValueByType-java-:

headerValueByType
=================

Description
-----------
Traverses the list of request headers and extracts the first header of the given type.

The ``headerValueByType`` directive finds a header of the given type in the list of request header. If no header of
the given type is found the request is rejected with a ``MissingHeaderRejection``.

If the header is expected to be missing in some cases or to customize handling when the header
is missing use the :ref:`-optionalHeaderValueByType-java-` directive instead.

.. note::
  Custom headers will only be matched by this directive if they extend ``ModeledCustomHeader``
  from the Scala DSL and there is currently no API for the Java DSL (Ticket #20415)

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
