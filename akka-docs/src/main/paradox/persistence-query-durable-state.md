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

Akka persistence query complements @ref:[Durable State Behaviors](typed/persistence-durable-state.md) by providing a universal asynchronous stream based
query interface that various state store plugins can implement in order to expose their query capabilities.

The most typical use case of persistence query is implementing the so-called query side (also known as "read side")
in the popular CQRS architecture pattern - in which the writing side of the application (e.g. implemented using Akka
persistence) is completely separated from the "query side". Akka Persistence Query itself is *not* directly the query
side of an application, however it can help to migrate data from the write side to the query side database. In very
simple scenarios Persistence Query may be powerful enough to fulfill the query needs of your app, however we highly
recommend (in the spirit of CQRS) of splitting up the write/read sides into separate datastores as the need arises.

## Using query with Akka Projections

Akka Persistence and Akka Projections together can be used to develop an Event Sourced CQRS application where the 
durable state is stored in a database and fetched as an asynchronous stream to the user. Currently queries on 
durable state, provided by the interface in `DurableStateStoreQuery`, is used to implement Akka Projections' 
tag based searches.
