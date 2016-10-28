<a id="pathend-java"></a>
# pathEnd

## Description

Only passes the request to its inner route if the unmatched path of the `RequestContext` is empty, i.e. the request
path has been fully matched by a higher-level @ref[path](path.md#path-java) or @ref[pathPrefix-java](pathPrefix.md#pathprefix-java) directive.

This directive is a simple alias for `rawPathPrefix(PathEnd)` and is mostly used on an
inner-level to discriminate "path already fully matched" from other alternatives (see the example below).

## Example

@@snip [PathDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/PathDirectivesExamplesTest.java) { #path-end }