# cachingProhibited

## Description

This directive is used to filter out requests that forbid caching. It is used as a building block of the @ref[cache](cache.md) directive to prevent caching if the client requests so.

## Example

@@snip [CachingDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/CachingDirectivesExamplesTest.java) { #caching-prohibited }
