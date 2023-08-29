---
project.description: Migrating to Akka 2.9.
---
# Migration Guide 2.8.x to 2.9.x

Akka 2.9.x is binary backwards compatible with 2.8.x with the ordinary exceptions listed in the
@ref:[Binary Compatibility Rules](../common/binary-compatibility-rules.md).

No configuration changes are needed for updating an application from Akka 2.8.x to 2.9.x.

Rolling updates of Akka Cluster from Akka 2.8.x to 2.9.x is fully supported.

A few deprecated features has been removed in Akka 2.9.x, see sections below.

## Cluster Client

Cluster client has been deprecated since Akka 2.6.0 (2019-11-06). Details on how to migrate can be found in the Akka 
2.6 docs here: https://doc.akka.io/docs/akka/2.6/cluster-client.html#migration-to-akka-grpc


## Typed Actor

The old "Typed Actor" API (`akka.actor.TypedActor`) has been deprecated since Akka 2.6.0 (2019-11-06) and has been dropped.
No detailed migration guide exists, the recommendation is to move to the new Akka Typed APIs. 

## Akka SSLConfig

The Akka SSLConfig convenience and methods accepting it has been deprecated since Akka 2.6.0 and has been dropped. 
Usage should be replaced with directly creating a `javax.net.ssl.SSLEngine` using the JDK APIs.
