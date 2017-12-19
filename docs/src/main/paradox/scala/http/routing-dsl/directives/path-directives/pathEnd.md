# pathEnd

@@@ div { .group-scala }

## Signature

@@signature [PathDirectives.scala]($akka-http$/akka-http/src/main/scala/akka/http/scaladsl/server/directives/PathDirectives.scala) { #pathEnd }

@@@

## Description

Only passes the request to its inner route if the unmatched path of the @unidoc[RequestContext] is empty, i.e. the request
path has been fully matched by a higher-level @ref[path](path.md) or @ref[pathPrefix](pathPrefix.md) directive.

This directive is a simple alias for `rawPathPrefix(PathEnd)` and is mostly used on an
inner-level to discriminate "path already fully matched" from other alternatives (see the example below). For a comparison between path directives check @ref[Overview of path directives](index.md#overview-path).

## Example

Scala
:  @@snip [PathDirectivesExamplesSpec.scala]($test$/scala/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala) { #pathEnd- }

Java
:  @@snip [PathDirectivesExamplesTest.java]($test$/java/docs/http/javadsl/server/directives/PathDirectivesExamplesTest.java) { #path-end }
