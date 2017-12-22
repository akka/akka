# host

Filter requests matching conditions against the hostname part of the Host header value
in the request.

@@@ div { .group-scala }

## Signature

@@signature [HostDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/HostDirectives.scala) { #host }

@@@

## Description

@@@ div { .group-scala }

The `def host(hostNames: String*)` overload rejects all requests with a hostname different from the given ones.

The `def host(predicate: String ⇒ Boolean)` overload rejects all requests for which the hostname does
not satisfy the given predicate.

@@@

@@@ div { .group-java }

There are a few variants:

 * reject all requests with a hostname different from the given ones
 * reject all requests for which the hostname does not satisfy the given predicate
 * reject all requests for which the hostname does not satisfy the given regular expression

@@@

The @scala[`def host(regex: Regex)` overload]@java[regular expression matching] works a little bit different: it rejects all requests with a hostname
that doesn't have a prefix matching the given regular expression and also extracts a `String` to its
inner route following this rules:

>
 * For all matching requests the prefix string matching the regex is extracted and passed to the inner route.
 * If the regex contains a capturing group only the string matched by this group is extracted.
 * If the regex contains more than one capturing group an `IllegalArgumentException` is thrown.

## Example

Matching a list of hosts:

Scala
:  @@snip [HostDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/HostDirectivesExamplesSpec.scala) { #list-of-hosts }

Java
:  @@snip [HostDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/HostDirectivesExamplesTest.java) { #host1 }

Making sure the host satisfies the given predicate

Scala
:  @@snip [HostDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/HostDirectivesExamplesSpec.scala) { #predicate }

Java
:  @@snip [HostDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/HostDirectivesExamplesTest.java) { #host2 }

Using a regular expressions:

Scala
:  @@snip [HostDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/HostDirectivesExamplesSpec.scala) { #using-regex }

Java
:  @@snip [HostDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/HostDirectivesExamplesTest.java) { #matchAndExtractHost }

Beware that in the case of introducing multiple capturing groups in the regex such as in the case bellow, the
directive will fail at runtime, at the moment the route tree is evaluated for the first time. This might cause
your http handler actor to enter in a fail/restart loop depending on your supervision strategy.

Scala
:  @@snip [HostDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/HostDirectivesExamplesSpec.scala) { #failing-regex }

Java
:  @@snip [HostDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/HostDirectivesExamplesTest.java) { #failing-matchAndExtractHost }
