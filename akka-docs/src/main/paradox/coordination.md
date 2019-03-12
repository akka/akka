# Coordination

@@@ warning

This module is currently marked as @ref:[may change](../common/may-change.md). It is ready to be used
in production but the API may change without warning or a deprecation period.

@@@

Akka Coordination is a set of tools for distributed coordination.

## Dependency

@@dependency[sbt,Gradle,Maven] {
  group="com.typesafe.akka"
  artifact="akka-coordination_$scala.binary_version$"
  version="$akka.version$"
}

## Lease

The lease is a pluggable API for a distributed lock. Implementations extend
the @scala[`akka.coordination.lease.scaladsl.Lease`]@java[`akka.coordination.lease.javadsl.Lease`].




