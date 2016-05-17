.. _-optionalHeaderValue-java-:

optionalHeaderValue
===================

Description
-----------
Traverses the list of request headers with the specified function and extracts the first value the function returns as
``Optional[value]``.

The ``optionalHeaderValue`` directive is similar to the :ref:`-headerValue-java-` directive but always extracts an ``Option``
value instead of rejecting the request if no matching header could be found.

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
