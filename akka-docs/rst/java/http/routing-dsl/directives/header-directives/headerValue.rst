.. _-headerValue-java-:

headerValue
===========

Description
-----------
Traverses the list of request headers with the specified function and extracts the first value the function returns as
``Optional[value]``.

The :ref:`-headerValue-java-` directive is a mixture of ``map`` and ``find`` on the list of request headers. The specified function
is called once for each header until the function returns ``Optional(value)``. This value is extracted and presented to the
inner route. If the function throws an exception the request is rejected with a ``MalformedHeaderRejection``. If the
function returns ``Optional.empty`` for every header the request is rejected as "NotFound".

This directive is the basis for building other request header related directives.

See also :ref:`-headerValuePF-java-` for a nicer syntactic alternative.

Example
-------
.. includecode:: ../../../../code/docs/http/javadsl/server/directives/HeaderDirectivesExamplesTest.java#headerValue