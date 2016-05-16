.. _-reject-java-:

reject
======

Description
-----------
Explicitly rejects the request optionally using the given rejection(s).

``reject`` uses the given rejection instances (which might be the empty ``Seq``) to construct a ``Route`` which simply
calls ``requestContext.reject``. See the chapter on :ref:`rejections-java` for more information on what this means.

After the request has been rejected at the respective point it will continue to flow through the routing structure in
the search for a route that is able to complete it.

The explicit ``reject`` directive is used mostly when building :ref:`Custom Directives`, e.g. inside of a ``flatMap``
modifier for "filtering out" certain cases.


Example
-------
TODO: Example snippets for JavaDSL are subject to community contributions! Help us complete the docs, read more about it here: `write example snippets for Akka HTTP Java DSL #20466 <https://github.com/akka/akka/issues/20466>`_.
