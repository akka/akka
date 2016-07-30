Migration Guide between experimental builds of Akka HTTP (2.4.x)
================================================================

General notes
-------------
Please note that Akka HTTP consists of a number of modules, most notably `akka-http-core`
which is **stable** and won't be breaking compatibility without a proper deprecation cycle,
and `akka-http` which contains the routing DSLs which is **experimental** still.

The following migration guide explains migration steps to be made between breaking
versions of the **experimental** part of Akka HTTP. 

.. note:: 
  Please note that experimental modules are allowed (and are expected to) break compatibility
  in search of the best API we can offer, before the API is frozen in a stable release. 
  
  Please read :ref:`BinCompatRules` to understand in depth what bin-compat rules are, and where they are applied.

Akka HTTP 2.4.7 -> 2.4.8
------------------------

``SecurityDirectives#challengeFor`` has moved
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
The ``challengeFor`` directive was actually more like a factory for ``HttpChallenge``,
thus it was moved to become such. It is now available as ``akka.http.javadsl.model.headers.HttpChallenge#create[Basic|OAuth2]``
for JavaDSL and ``akka.http.scaladsl.model.headers.HttpChallenges#[basic|oAuth2]`` for ScalaDSL.

Akka HTTP 2.4.8 -> 2.4.9
------------------------

Java DSL Package structure changes
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
We have aligned the package structure of the Java based DSL with the Scala based DSL
and moved classes that were in the wrong or unexpected places around a bit. This means
that Java DSL users must update their imports as follows:

Classes dealing with unmarshalling and marshalling used to reside in ``akka.http.javadsl.server``,
but are now available from the packages ``akka.http.javadsl.unmarshalling`` and ``akka.http.javadsl.marshalling``.

``akka.http.javadsl.server.Coder`` is now ``akka.http.javadsl.coding.Coder``.

``akka.http.javadsl.server.RegexConverters`` is now ``akka.http.javadsl.common.RegexConverters``.
