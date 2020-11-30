# Example projects

The following example projects can be downloaded. They contain build files and have instructions
of how to run.

## Quickstart

@scala[[Quickstart Guide](https://developer.lightbend.com/guides/akka-quickstart-scala/)]
@java[[Quickstart Guide](https://developer.lightbend.com/guides/akka-quickstart-java/)]
 
The *Quickstart* guide walks you through example code that introduces how to define actor systems, actors, and
messages as well as how to use the test module and logging.

## FSM

@java[@extref[FSM example project](samples:akka-samples-fsm-java)]
@scala[@extref[FSM example project](samples:akka-samples-fsm-scala)]

This project contains a Dining Hakkers sample illustrating how to model a Finite State Machine (FSM) with actors.

## Cluster

@java[@extref[Cluster example project](samples:akka-samples-cluster-java)]
@scala[@extref[Cluster example project](samples:akka-samples-cluster-scala)]

This project contains samples illustrating different Cluster features, such as
subscribing to cluster membership events, and sending messages to actors running on nodes in the cluster
with Cluster aware routers.

It also includes Multi JVM Testing with the `sbt-multi-jvm` plugin.

## Distributed Data

@java[@extref[Distributed Data example project](samples:akka-samples-distributed-data-java)]
@scala[@extref[Distributed Data example project](samples:akka-samples-distributed-data-scala)]

This project contains several samples illustrating how to use Distributed Data.

## Cluster Sharding

@java[@extref[Sharding example project](samples:akka-samples-cluster-sharding-java)]
@scala[@extref[Sharding example project](samples:akka-samples-cluster-sharding-scala)]

This project contains a KillrWeather sample illustrating how to use Cluster Sharding.

## Persistence

@java[@extref[Persistence example project](samples:akka-samples-persistence-java)]
@scala[@extref[Persistence example project](samples:akka-samples-persistence-scala)]

This project contains a Shopping Cart sample illustrating how to use Akka Persistence.

## CQRS

The @extref[Microservices with Akka tutorial](platform-guide:microservices-tutorial/) contains a
Shopping Cart sample illustrating how to use Event Sourcing and Projections together. The events are
tagged to be consumed by even processors to build other representations from the events, or publish the events
to other services.

## Replicated Event Sourcing

@java[@extref[Multi-DC Persistence example project](samples:akka-samples-persistence-dc-java)]
@scala[@extref[Multi-DC Persistence example project](samples:akka-samples-persistence-dc-scala)]

Illustrates how to use @ref:[Replicated Event Sourcing](../typed/replicated-eventsourcing.md) that supports
active-active persistent entities across data centers.

## Cluster with Docker

@java[@extref[Cluster with docker-compse example project](samples:akka-sample-cluster-docker-compose-java)]
@scala[@extref[Cluster with docker-compose example project](samples:akka-sample-cluster-docker-compose-scala)]

Illustrates how to use Akka Cluster with Docker compose.

## Cluster with Kubernetes

@extref[Cluster with Kubernetes example project](samples:akka-sample-cluster-kubernetes-java)

This sample illustrates how to form an Akka Cluster with Akka Bootstrap when running in Kubernetes.

## Distributed workers

@extref[Distributed workers example project](samples:akka-samples-distributed-workers-scala)

This project demonstrates the work pulling pattern using Akka Cluster.

## Kafka to Cluster Sharding 

@extref[Kafka to Cluster Sharding example project](samples:akka-samples-kafka-to-sharding)

This project demonstrates how to use the External Shard Allocation strategy to co-locate the consumption of Kafka
partitions with the shard that processes the messages.


