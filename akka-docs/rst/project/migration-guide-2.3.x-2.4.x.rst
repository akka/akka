.. _migration-2.4:

################################
 Migration Guide 2.3.x to 2.4.x
################################

The 2.4 release contains some structural changes that require some
simple, mechanical source-level changes in client code.

When migrating from earlier versions you should first follow the instructions for
migrating :ref:`1.3.x to 2.0.x <migration-2.0>` and then :ref:`2.0.x to 2.1.x <migration-2.1>`
and then :ref:`2.1.x to 2.2.x <migration-2.2>` and then :ref:`2.2.x to 2.3.x <migration-2.3>`.

TestKit.remaining throws AssertionError
=======================================

In earlier versions of Akka `TestKit.remaining` returned the default timeout configurable under
"akka.test.single-expect-default". This was a bit confusing and thus it has been changed to throw an
AssertionError if called outside of within. The old behavior however can still be achieved by
calling `TestKit.remainingOrDefault` instead.

Removed Deprecated Features
===========================

The following, previously deprecated, features have been removed:

* akka-dataflow
* akka-transactor
* durable mailboxes (akka-mailboxes-common, akka-file-mailbox)
* Cluster.publishCurrentClusterState
* akka.cluster.auto-down, replaced by akka.cluster.auto-down-unreachable-after in Akka 2.3
