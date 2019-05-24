# Cluster Singleton

## Dependency

To use Cluster Singleton, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-cluster-typed_$scala.binary_version$
  version=$akka.version$
}

## Introduction

For some use cases it is convenient and sometimes also mandatory to ensure that
you have exactly one actor of a certain type running somewhere in the cluster.

Some examples:

 * single point of responsibility for certain cluster-wide consistent decisions, or
coordination of actions across the cluster system
 * single entry point to an external system
 * single master, many workers
 * centralized naming service, or routing logic

Using a singleton should not be the first design choice. It has several drawbacks,
such as single-point of bottleneck. Single-point of failure is also a relevant concern,
but for some cases this feature takes care of that by making sure that another singleton
instance will eventually be started.

## Example

Any `Behavior` can be run as a singleton. E.g. a basic counter:

Scala
:  @@snip [SingletonCompileOnlySpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/SingletonCompileOnlySpec.scala) { #counter }

Java
:  @@snip [SingletonCompileOnlyTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/SingletonCompileOnlyTest.java) { #counter }

Then on every node in the cluster, or every node with a given role, use the `ClusterSingleton` extension
to spawn the singleton. An instance will per data centre of the cluster:


Scala
:  @@snip [SingletonCompileOnlySpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/SingletonCompileOnlySpec.scala) { #singleton }

Java
:  @@snip [SingletonCompileOnlyTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/SingletonCompileOnlyTest.java) { #import #singleton }

## Supervision

The default @ref[supervision strategy](./fault-tolerance.md) when an exception is thrown is for an actor to be stopped. 
The above example overrides this to `restart` to ensure it is always running. Another option would be to restart with 
a backoff: 


Scala
:  @@snip [SingletonCompileOnlySpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/SingletonCompileOnlySpec.scala) { #backoff}

Java
:  @@snip [SingletonCompileOnlyTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/SingletonCompileOnlyTest.java) { #backoff}

Be aware that this means there will be times when the singleton won't be running as restart is delayed.
See @ref[Fault Tolerance](./fault-tolerance.md) for a full list of supervision options.


## Application specific stop message

An application specific `stopMessage` can be used to close the resources before actually stopping the singleton actor. 
This `stopMessage` is sent to the singleton actor to tell it to finish its work, close resources, and stop. The hand-over to the new oldest node is completed when the
singleton actor is terminated.
If the shutdown logic does not include any asynchronous actions it can be executed in the `PostStop` signal handler.

Scala
:  @@snip [SingletonCompileOnlySpec.scala](/akka-cluster-typed/src/test/scala/docs/akka/cluster/typed/SingletonCompileOnlySpec.scala) { #stop-message }

Java
:  @@snip [SingletonCompileOnlyTest.java](/akka-cluster-typed/src/test/java/jdocs/akka/cluster/typed/SingletonCompileOnlyTest.java) { #stop-message }


## Accessing singleton of another data centre

TODO

