# alwaysCache

## Description

Like @ref[cache](cache.md) but disregards a `Cache-Control` request header.

## Example

Scala
:  @@snip [CachingDirectivesExamplesSpec.scala](../../../../../../../test/scala/docs/http/scaladsl/server/directives/CachingDirectivesExamplesSpec.scala) { #always-cache }

Java
:  @@snip [CachingDirectivesExamplesTest.java](../../../../../../../test/java/docs/http/javadsl/server/directives/CachingDirectivesExamplesTest.java) { #always-cache }
