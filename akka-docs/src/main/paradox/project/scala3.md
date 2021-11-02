# Scala 3 support

Akka has experimental support for Scala 3.

## Using 2.13 artifacts in Scala 3

You can use [CrossVersion.for3Use2\_13](https://scala-lang.org/blog/2021/04/08/scala-3-in-sbt.html#using-scala-213-libraries-in-scala-3)
to use the regular 2.13 Akka artifacts in a Scala 3 project. This has been
shown to be successful for Streams, HTTP and gRPC-heavy applications.

## Scala 3 artifacts

Starting with Akka version 2.6.18 (and on current [development snapshots](https://oss.sonatype.org/content/repositories/snapshots/com/typesafe/akka/akka-actor_3/)),
we are publishing experimental Scala 3 artifacts that can be used 'directly'
(without `CrossVersion`) with Scala 3.1.1-RC1. The 2.6.17 artifacts can be used only with nightly builds of Scala 3 (i.e. `3.1.1-RC1-bin-20211007-c041327-NIGHTLY`).

There are some known run-time problems with these artifacts still, which are
tracked under the umbrella issue [#30242](https://github.com/akka/akka/issues/30242).
We encourage you to try out these artifacts and report any findings.

We do not promise @ref:[binary compatibility](../common/binary-compatibility-rules.md) for these artifacts yet.
