# Testing

## Dependency

To use Akka Persistence and Actor TestKit, add the module to your project:

@@dependency[sbt,Maven,Gradle] {
  group1=com.typesafe.akka
  artifact1=akka-persistence-typed_$scala.binary_version$
  version1=$akka.version$
  group2=com.typesafe.akka
  artifact2=akka-actor-testkit-typed_$scala.binary_version$
  version2=$akka.version$
  scope2=test
}

## Unit testing

Unit testing of `EventSourcedBehavior` can be done with the @ref:[ActorTestKit](testing-async.md)
in the same way as other behaviors.

@ref:[Synchronous behavior testing](testing-sync.md) for `EventSourcedBehavior` is not supported yet, but
tracked in @github[issue #26338](#23712).

You need to configure a journal, and the in-memory journal is sufficient for unit testing. To enable the
in-memory journal you need to pass the following configuration to the @scala[`ScalaTestWithActorTestKit`]@java[`TestKitJunitResource`].

Scala
:  @@snip [AccountExampleDocSpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/AccountExampleDocSpec.scala) { #inmem-config }

Java
:  @@snip [AccountExampleDocTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/AccountExampleDocTest.java) { #inmem-config } 

Optionally you can also configure a snapshot store. To enable the file based snapshot store you need to pass the
following configuration to the @scala[`ScalaTestWithActorTestKit`]@java[`TestKitJunitResource`].

Scala
:  @@snip [AccountExampleDocSpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/AccountExampleDocSpec.scala) { #snapshot-store-config }

Java
:  @@snip [AccountExampleDocTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/AccountExampleDocTest.java) { #snapshot-store-config }

Then you can `spawn` the `EventSourcedBehavior` and verify the outcome of sending commands to the actor using
the facilities of the @ref:[ActorTestKit](testing-async.md).

A full test for the `AccountEntity`, which is shown in the @ref:[Style Guide](style-guide.md), may look like this:

Scala
:  @@snip [AccountExampleDocSpec.scala](/akka-cluster-sharding-typed/src/test/scala/docs/akka/cluster/sharding/typed/AccountExampleDocSpec.scala) { #test }

Java
:  @@snip [AccountExampleDocTest.java](/akka-cluster-sharding-typed/src/test/java/jdocs/akka/cluster/sharding/typed/AccountExampleDocTest.java) { #test }  

Note that each test case is using a different `PersistenceId` to not interfere with each other.

## Integration testing

The in-memory journal and file based snapshot store can be used also for integration style testing of a single
`ActorSystem`, for example when using Cluster Sharding with a single Cluster node.

For tests that involve more than one Cluster node you have to use another journal and snapshot store.
While it's possible to use the @ref:[Persistence Plugin Proxy](../persistence-plugins.md#persistence-plugin-proxy)
it's often better and more realistic to use a real database.

See [akka-samples issue #128](https://github.com/akka/akka-samples/issues/128).    
