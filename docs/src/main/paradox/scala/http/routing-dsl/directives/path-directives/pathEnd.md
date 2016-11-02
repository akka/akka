<a id="pathend"></a>
# pathEnd

## Signature

@@signature [PathDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/PathDirectives.scala) { #pathEnd }

## Description

Only passes the request to its inner route if the unmatched path of the `RequestContext` is empty, i.e. the request
path has been fully matched by a higher-level @ref[path](path.md#path) or @ref[pathPrefix](pathPrefix.md#pathprefix) directive.

This directive is a simple alias for `rawPathPrefix(PathEnd)` and is mostly used on an
inner-level to discriminate "path already fully matched" from other alternatives (see the example below).

## Example

@@snip [PathDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala) { #pathEnd- }