<a id="pathsingleslash"></a>
# pathSingleSlash

## Signature

@@signature [PathDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/PathDirectives.scala) { #pathSingleSlash }

## Description

Only passes the request to its inner route if the unmatched path of the `RequestContext`
contains exactly one single slash.

This directive is a simple alias for `pathPrefix(PathEnd)` and is mostly used for matching requests to the root URI
(`/`) on an inner-level to discriminate "all path segments matched" from other alternatives (see the example below).

## Example

@@snip [PathDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala) { #pathSingleSlash- }