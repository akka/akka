# redirectToNoTrailingSlashIfPresent

@@@ div { .group-scala }

## Signature

@@signature [PathDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/PathDirectives.scala) { #redirectToNoTrailingSlashIfPresent }

@@@

## Description

If the requested path does end with a trailing `/` character,
redirects to the same path without that trailing slash..

Redirects the HTTP Client to the same resource yet without the trailing `/`, in case the request contained it.
When redirecting an HttpResponse with the given redirect response code (i.e. `MovedPermanently` or `TemporaryRedirect`
etc.) as well as a simple HTML page containing a "*click me to follow redirect*" link to be used in case the client can not,
or refuses to for security reasons, automatically follow redirects.

Please note that the inner paths **MUST NOT** end with an explicit trailing slash (e.g. `"things"./`)
for the re-directed-to route to match.

A good read on the subject of how to deal with trailing slashes is available on [Google Webmaster Central - To Slash or not to Slash](http://googlewebmastercentral.blogspot.de/2010/04/to-slash-or-not-to-slash.html).

See also @ref[redirectToTrailingSlashIfMissing](redirectToTrailingSlashIfMissing.md) which achieves the opposite - redirecting paths in case they do *not* have a trailing slash.

## Example

Scala
:  @@snip [PathDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala) { #redirectToNoTrailingSlashIfPresent-0 }

Java
:  @@snip [PathDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/PathDirectivesExamplesTest.java) { #redirect-notrailing-slash-present }
