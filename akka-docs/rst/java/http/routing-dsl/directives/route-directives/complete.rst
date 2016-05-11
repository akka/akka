.. _-complete-java-:

complete
========

Description
-----------

Completes the request using the given argument(s).

``complete`` uses the given arguments to construct a ``Route`` which simply calls ``complete`` on the ``RequestContext``
with the respective ``HttpResponse`` instance.
Completing the request will send the response "back up" the route structure where all the logic runs that wrapping
directives have potentially chained into the :class:`RouteResult` future transformation chain.

Please note that the ``complete`` directive has multiple variants, like 

Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
