.. _-headerValuePF-java-:

headerValuePF
=============

Description
-----------
Calls the specified partial function with the first request header the function is ``isDefinedAt`` and extracts the
result of calling the function.

The ``headerValuePF`` directive is an alternative syntax version of :ref:`-headerValue-java-`.

If the function throws an exception the request is rejected with a ``MalformedHeaderRejection``.

If the function is not defined for any header the request is rejected as "NotFound".

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
