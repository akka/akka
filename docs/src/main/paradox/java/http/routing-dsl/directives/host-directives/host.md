# host

Filter requests matching conditions against the hostname part of the Host header value
in the request.

There are a few variants:

 * reject all requests with a hostname different from the given ones
 * reject all requests for which the hostname does not satisfy the given predicate
 * reject all requests for which the hostname does not satisfy the given regular expression

The regular expression matching works a little bit different: it rejects all requests with a hostname
that doesn't have a prefix matching the given regular expression and also extracts a `String` to its
inner route following this rules:

>
 * For all matching requests the prefix string matching the regex is extracted and passed to the inner route.
 * If the regex contains a capturing group only the string matched by this group is extracted.
 * If the regex contains more than one capturing group an `IllegalArgumentException` is thrown.

## Example

Matching a list of hosts:

@@snip [HostDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/HostDirectivesExamplesTest.java) { #host1 }

Making sure the host satisfies the given predicate

@@snip [HostDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/HostDirectivesExamplesTest.java) { #host2 }

Using a regular expressions:

@@snip [HostDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/HostDirectivesExamplesTest.java) { #matchAndExtractHost }

Beware that in the case of introducing multiple capturing groups in the regex such as in the case bellow, the
directive will fail at runtime, at the moment the route tree is evaluated for the first time. This might cause
your http handler actor to enter in a fail/restart loop depending on your supervision strategy.

Scala
:  @@snip [HostDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/HostDirectivesExamplesSpec.scala) { #failing-regex }

Java
:  @@snip [HostDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/HostDirectivesExamplesTest.java) { #failing-matchAndExtractHost }
