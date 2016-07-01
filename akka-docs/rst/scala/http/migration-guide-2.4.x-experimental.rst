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

`SecurityDirectives#challengeFor` has moved
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
The `challengeFor` directive was actually more like a factory for `HttpChallenge`,
thus it was moved to become such. It is now available as `akka.http.javadsl.model.headers.HttpChallenge#create[Basic|OAuth2]`
for JavaDSL and `akka.http.scaladsl.model.headers.HttpChallenges#[basic|oAuth2]` for ScalaDSL.
