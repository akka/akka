---
project.description: An intuitive and safe way to do asynchronous, non-blocking backpressured stream processing.
---
# Streams

## Module info

To use Akka Streams, add the module to your project:

@@dependency[sbt,Maven,Gradle] {
  bomGroup=com.typesafe.akka bomArtifact=akka-bom_$scala.binary.version$ bomVersionSymbols=AkkaVersion
  symbol1=AkkaVersion
  value1="$akka.version$"
  group="com.typesafe.akka"
  artifact="akka-stream_$scala.binary.version$"
  version=AkkaVersion
  group2="com.typesafe.akka"
  artifact2="akka-stream-testkit_$scala.binary.version$"
  version2=AkkaVersion
  scope2=test
}

@@project-info{ projectId="akka-stream" }

@@toc { depth=2 }

@@@ index

* [stream-introduction](stream-introduction.md)
* [stream-quickstart](stream-quickstart.md)
* [../general/stream/stream-design](../general/stream/stream-design.md)
* [stream-flows-and-basics](stream-flows-and-basics.md)
* [stream-graphs](stream-graphs.md)
* [stream-composition](stream-composition.md)
* [stream-rate](stream-rate.md)
* [stream-context](stream-context.md)
* [stream-dynamic](stream-dynamic.md)
* [stream-customize](stream-customize.md)
* [futures-interop](futures-interop.md)
* [actor-interop](actor-interop.md)
* [reactive-streams-interop](reactive-streams-interop.md)
* [stream-error](stream-error.md)
* [stream-io](stream-io.md)
* [stream-refs](stream-refs.md)
* [stream-parallelism](stream-parallelism.md)
* [stream-testkit](stream-testkit.md)
* [stream-substream](stream-substream.md)
* [stream-cookbook](stream-cookbook.md)
* [../general/stream/stream-configuration](../general/stream/stream-configuration.md)
* [operators](operators/index.md)

@@@
