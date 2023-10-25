# Other Akka modules

This page describes modules that compliment libraries from the Akka core.  See [this overview](https://doc.akka.io/docs/akka/current/typed/guide/modules.html) instead for a guide on the core modules.

## [Akka HTTP](https://doc.akka.io/docs/akka-http/current/)

A full server- and client-side HTTP stack on top of akka-actor and akka-stream.

## [Akka gRPC](https://doc.akka.io/docs/akka-grpc/current/)

Akka gRPC provides support for building streaming gRPC servers and clients on top of Akka Streams.

## [Alpakka](https://doc.akka.io/docs/alpakka/current/)

Alpakka is a Reactive Enterprise Integration library for Java and Scala, based on Reactive Streams and Akka.

## [Alpakka Kafka Connector](https://doc.akka.io/docs/alpakka-kafka/current/)

The Alpakka Kafka Connector connects Apache Kafka with Akka Streams.


## @extref[Akka Projections](akka-projection:)

Akka Projections let you process a stream of events or records from a source to a projected model or external system.


## [Cassandra Plugin for Akka Persistence](https://doc.akka.io/docs/akka-persistence-cassandra/current/)

An Akka Persistence journal and snapshot store backed by Apache Cassandra.


## [JDBC Plugin for Akka Persistence](https://doc.akka.io/docs/akka-persistence-jdbc/current/)

An Akka Persistence journal and snapshot store for use with JDBC-compatible databases. This implementation relies on [Slick](https://scala-slick.org/).

## [R2DBC Plugin for Akka Persistence](https://doc.akka.io/docs/akka-persistence-r2dbc/current/)

An Akka Persistence journal and snapshot store for use with R2DBC-compatible databases. This implementation relies on [R2DBC](https://r2dbc.io/).

## Akka Management

* @extref:[Akka Management](akka-management:) provides a central HTTP endpoint for Akka management extensions.
* @extref:[Akka Cluster Bootstrap](akka-management:bootstrap/) helps bootstrapping an Akka cluster using Akka Discovery.
* @extref:[Akka Management Kubernetes Rolling Updates](akka-management:rolling-updates.html) for smooth rolling updates.
* @extref:[Akka Management Cluster HTTP](akka-management:cluster-http-management.html) provides HTTP endpoints for introspecting and managing Akka clusters.
* @extref:[Akka Discovery for Kubernetes, Consul, Marathon, and AWS](akka-management:discovery/)
* @extref:[Kubernetes Lease](akka-management:kubernetes-lease.html)

## Akka Diagnostics

* [Akka Thread Starvation Detector](https://doc.akka.io/docs/akka-diagnostics/current/starvation-detector.html)
* [Akka Configuration Checker](https://doc.akka.io/docs/akka-diagnostics/current/config-checker.html)

## Akka Insights

Intelligent monitoring and observability purpose-built for Akka: [Lightbend Telemetry](https://developer.lightbend.com/docs/telemetry/current/home.html)

## Community Projects

Akka has a vibrant and passionate user community, the members of which have created many independent projects using Akka as well as extensions to it. See [Community Projects](https://akka.io/community/).

