# CachingDirectives

Use these directives to "wrap" expensive operations with a caching layer that
runs the wrapped operation only once and returns the the cached value for all
future accesses for the same key (as long as the respective entry has not expired).
See @ref[caching](../../../common/caching.md) for an introduction to how the
caching support works.

## Dependency

To use Akka HTTP Caching, add the module to your project:

@@dependency[sbt,Gradle,Maven] {
  group="com.typesafe.akka"
  artifact="akka-http-caching_$scala.binary.version$"
  version="$project.version$"
}

## Imports

Directives are available by importing:

Scala
:  @@snip [HeaderDirectivesExamplesSpec.scala]($root$/src/test/scala/docs/http/scaladsl/server/directives/CachingDirectivesExamplesSpec.scala) { #caching-directives-import }

Java
:   @@snip [CachingDirectivesExamplesTest.java]($root$/src/test/java/docs/http/javadsl/server/directives/CachingDirectivesExamplesTest.java) { #caching-directives-import }

@@toc { depth=1 }

@@@ index

* [cache](cache.md)
* [alwaysCache](alwaysCache.md)
* [cachingProhibited](cachingProhibited.md)

@@@
