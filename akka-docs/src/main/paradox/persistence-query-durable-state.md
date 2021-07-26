---
project.description: Query side to Akka Persistence allowing for building CQRS applications using durable state.
---
# Persistence Query

## Dependency

To use Persistence Query, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  bomGroup=com.typesafe.akka bomArtifact=akka-bom_$scala.binary.version$ bomVersionSymbols=AkkaVersion
  symbol1=AkkaVersion
  value1="$akka.version$"
  group=com.typesafe.akka
  artifact=akka-persistence-query_$scala.binary.version$
  version=AkkaVersion
}

This will also add dependency on the @ref[Akka Persistence](persistence.md) module.

## Introduction

Akka persistence query provides a query interface to @ref:[Durable State Behaviors](typed/persistence-durable-state.md).
These queries are based on asynchronous streams, very much like the ones offered in the @ref:[Event Sourcing](persistence-query.md)
based implementation. Various state store plugins can implement these interfaces in order to expose their query capabilities.

One of the rationales behind having a separate query module for Akka Persistence is for implementing the so-called 
query side (also known as "read side") in the popular CQRS architecture pattern - in which the writing side of the 
application (e.g. implemented using Akka persistence) is completely separated from the "query side".

## Using query with Akka Projections

Akka Persistence and Akka Projections together can be used to develop a CQRS application where the 
durable state is stored in a database and fetched as an asynchronous stream to the user. Currently queries on 
durable state, provided by the interface in `DurableStateStoreQuery`, is used to implement Akka Projections' 
tag based searches.
