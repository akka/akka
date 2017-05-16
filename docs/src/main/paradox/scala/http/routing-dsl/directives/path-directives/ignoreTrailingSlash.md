<a id="ignoreTrailingSlash"></a>
# ignoreTrailingSlash

## Signature

@@signature [PathDirectives.scala](../../../../../../../../../akka-http/src/main/scala/akka/http/scaladsl/server/directives/PathDirectives.scala) { #ignoreTrailingSlash }

## Description

If the requested path ends with a trailing `/` character and the inner route is rejected with an empty `Rejection` list, 
it retries the inner route it removing the trailing `/` character. Similarly, it retries adding a trailing `/` character if the original requested path doesn't end with a `/` character. 

This directive will retry the inner route with a "flipped" trailing slash only if the mentioned inner route is rejected
with an empty `Rejection` list.

@@@ note
Please note that enclosing routes with this directive might cause double evaluation in case of unhandled request paths. 
This may be expensive when enclosing big route trees. Use with care.
@@@

See also @ref[redirectToNoTrailingSlashIfPresent](redirectToNoTrailingSlashIfPresent.md#redirecttonotrailingslashifpresent) and @ref[redirectToTrailingSlashIfMissing](redirectToTrailingSlashIfMissing.md#redirecttotrailingslashifmissing) for other ways to accomplish a similar thing. 

## Example

@@snip [PathDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/PathDirectivesExamplesSpec.scala) { #ignoreTrailingSlash }
