.. _-host-:

host
====

Signature
---------

.. includecode2:: /../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/HostDirectives.scala
   :snippet: host


Description
-----------
Filter requests matching conditions against the hostname part of the Host header value in the request.

The ``def host(hostNames: String*)`` overload rejects all requests with a hostname different from the given ones.

The ``def host(predicate: String ⇒ Boolean)`` overload rejects all requests for which the hostname does
not satisfy the given predicate.

The ``def host(regex: Regex)`` overload works a little bit different: it rejects all requests with a hostname
that doesn't have a prefix matching the given regular expression and also extracts a ``String`` to its
inner route following this rules:

   * For all matching requests the prefix string matching the regex is extracted and passed to the inner route.
   * If the regex contains a capturing group only the string matched by this group is extracted.
   * If the regex contains more than one capturing group an ``IllegalArgumentException`` is thrown.


Example
-------

Matching a list of hosts:

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/HostDirectivesExamplesSpec.scala
   :snippet: list-of-hosts

Making sure the host satisfies the given predicate

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/HostDirectivesExamplesSpec.scala
   :snippet: predicate

Using a regular expressions:

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/HostDirectivesExamplesSpec.scala
   :snippet: using-regex

Beware that in the case of introducing multiple capturing groups in the regex such as in the case bellow, the
directive will fail at runtime, at the moment the route tree is evaluated for the first time. This might cause
your http handler actor to enter in a fail/restart loop depending on your supervision strategy.

.. includecode2:: ../../../../code/docs/http/scaladsl/server/directives/HostDirectivesExamplesSpec.scala
   :snippet: failing-regex

