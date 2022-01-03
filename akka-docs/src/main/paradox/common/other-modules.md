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


## [Akka Projections](https://doc.akka.io/docs/akka-projection/current/)

Akka Projections let you process a stream of events or records from a source to a projected model or external system.


## [Cassandra Plugin for Akka Persistence](https://doc.akka.io/docs/akka-persistence-cassandra/current/)

An Akka Persistence journal and snapshot store backed by Apache Cassandra.


## [JDBC Plugin for Akka Persistence](https://doc.akka.io/docs/akka-persistence-jdbc/current/)

An Akka Persistence journal and snapshot store for use with JDBC-compatible databases. This implementation relies on [Slick](https://scala-slick.org/).

## [R2DBC Plugin for Akka Persistence](https://doc.akka.io/docs/akka-persistence-r2dbc/current/)

An Akka Persistence journal and snapshot store for use with R2DBC-compatible databases. This implementation relies on [R2DBC](https://r2dbc.io/).

## [Google Cloud Spanner Plugin for Akka Persistence](https://doc.akka.io/docs/akka-persistence-spanner/current/)

Use [Google Cloud Spanner](https://cloud.google.com/spanner/) as Akka Persistence journal and snapshot store. This integration relies on [Akka gRPC](https://doc.akka.io/docs/akka-grpc/current/).


## Akka Management

* [Akka Management](https://doc.akka.io/docs/akka-management/current/) provides a central HTTP endpoint for Akka management extensions.
* [Akka Cluster Bootstrap](https://doc.akka.io/docs/akka-management/current/bootstrap/) helps bootstrapping an Akka cluster using Akka Discovery.
* [Akka Management Cluster HTTP](https://doc.akka.io/docs/akka-management/current/cluster-http-management.html) provides HTTP endpoints for introspecting and managing Akka clusters.
* [Akka Discovery for Kubernetes, Consul, Marathon, and AWS](https://doc.akka.io/docs/akka-management/current/discovery/)
* [Kubernetes Lease](https://doc.akka.io/docs/akka-management/current/kubernetes-lease.html)

## Akka Resilience Enhancements

* [Akka Thread Starvation Detector](https://doc.akka.io/docs/akka-enhancements/current/starvation-detector.html)
* [Akka Configuration Checker](https://doc.akka.io/docs/akka-enhancements/current/config-checker.html)
* [Akka Diagnostics Recorder](https://doc.akka.io/docs/akka-enhancements/current/diagnostics-recorder.html)

## Akka Persistence Enhancements

* [Akka GDPR for Persistence](https://doc.akka.io/docs/akka-enhancements/current/gdpr/index.html)

## Community Projects

Akka has a vibrant and passionate user community, the members of which have created many independent projects using Akka as well as extensions to it. See [Community Projects](https://akka.io/community/).

## Related Projects Sponsored by Lightbend

### [Play Framework](https://www.playframework.com)

Play Framework provides a complete framework to build modern web applications, including tools for front end pipeline integration,
a HTML template language etc. It is built on top of Akka HTTP, and integrates well with Akka and Actors.

### [Lagom](https://www.lagomframework.com)

Lagom is a microservice framework which strives to be opinionated and encode best practices for building microservice systems with Akka and Play.

### [Lightbend Telemetry](https://developer.lightbend.com/docs/telemetry/current/home.html)

Distributed tracing, metrics and monitoring for Akka Actors, Cluster, HTTP and more.
