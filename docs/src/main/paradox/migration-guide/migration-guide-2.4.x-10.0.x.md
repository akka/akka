# Migration Guide between Akka HTTP 2.4.x and 10.0.x

## General notes

Please note that Akka HTTP consists of a number of modules, most notably *akka-http-core*
which is **stable** and won't be breaking compatibility without a proper deprecation cycle,
and *akka-http* which contains the routing DSLs which is **experimental** still.

The following migration guide explains migration steps to be made between breaking
versions of the **experimental** part of Akka HTTP. 

@@@ note
Please note that experimental modules are allowed (and are expected to) break compatibility
in search of the best API we can offer, before the API is frozen in a stable release.

Please read @extref[Binary Compatibility Rules](akka-docs:common/binary-compatibility-rules.html) to understand in depth what bin-compat rules are, and where they are applied.
@@@

## Akka HTTP 2.4.7 -> 2.4.8

### `SecurityDirectives#challengeFor` has moved

The `challengeFor` directive was actually more like a factory for @unidoc[HttpChallenge],
thus it was moved to become such. It is now available as `akka.http.javadsl.model.headers.HttpChallenge#create[Basic|OAuth2]`
for JavaDSL and `akka.http.scaladsl.model.headers.HttpChallenges#[basic|oAuth2]` for ScalaDSL.

## Akka HTTP 2.4.8 -> 2.4.9

### Java DSL Package structure changes

We have aligned the package structure of the Java based DSL with the Scala based DSL
and moved classes that were in the wrong or unexpected places around a bit. This means
that Java DSL users must update their imports as follows:

Classes dealing with unmarshalling and marshalling used to reside in `akka.http.javadsl.server`,
but are now available from the packages `akka.http.javadsl.unmarshalling` and `akka.http.javadsl.marshalling`.

`akka.http.javadsl.server.Coder` is now `akka.http.javadsl.coding.Coder`.

`akka.http.javadsl.server.RegexConverters` is now `akka.http.javadsl.common.RegexConverters`.

## Akka HTTP 2.4.11 -> 10.0.0

### Java DSL @unidoc[PathDirectives] used Scala Function type

The Java DSL for the following directives `pathPrefixText`, `rawPathPrefixTest`, `rawPathPrefix`, `pathSuffix`
accidentally used the Scala function type instead of the `java.util.function.Function` functional interface,
making them not usable in Java (unless compiled with Scala 2.12, which we're not yet shipping).

These directives now accept the proper Java types. If you worked around this issue before, please remove your workaround and upgrade.
Simply passing in a lambda expression will properly be expanded into the functional interface in these directives.
