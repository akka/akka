# Distributed Publish Subscribe in Cluster

@@@ note
For the Akka Classic documentation of this feature see @ref:[Classic Distributed Publish Subscribe](../distributed-pub-sub.md).
Classic Pub Sub can be used by leveraging the `.toClassic` adapters until @github[#26338](#26338).
@@@

@@project-info{ projectId="akka-cluster-typed" }

## Dependency

Until the new Distributed Publish Subscribe API, see @github[#26338](#26338), 
you can use Classic Distributed Publish Subscribe 
[coexisting](coexisting.md) with new Cluster and actors. To do this, add following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group="com.typesafe.akka"
  artifact="akka-cluster-tools_$scala.binary_version$"
  version="$akka.version$"
}

Add the new Cluster API if you don't already have it in an existing Cluster application:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-cluster-typed_$scala.binary_version$
  version=$akka.version$
}

@@@

## Sample project

Until @github[#26338](#26338), this example shows how to use 
@ref:[Classic Distributed Publish Subscribe](../distributed-pub-sub.md) with the new Cluster API.

