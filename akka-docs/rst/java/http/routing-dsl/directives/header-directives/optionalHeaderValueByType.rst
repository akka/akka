.. _-optionalHeaderValueByType-java-:

optionalHeaderValueByType
=========================

Description
-----------
Optionally extracts the value of the HTTP request header of the given type.

The ``optionalHeaderValueByType`` directive is similar to the :ref:`-headerValueByType-java-` directive but always extracts
an ``Optional`` value instead of rejecting the request if no matching header could be found.

.. note::
  Custom headers will only be matched by this directive if they extend ``ModeledCustomHeader``
  from the Scala DSL and there is currently no API for the Java DSL (Ticket #20415)

  To learn more about defining custom headers, read: :ref:`custom-headers-scala`.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
