# Persistence Query

Akka persistence query complements @ref:[Persistence](persistence.md) by providing a universal asynchronous stream based
query interface that various journal plugins can implement in order to expose their query capabilities.

The most typical use case of persistence query is implementing the so-called query side (also known as "read side")
in the popular CQRS architecture pattern - in which the writing side of the application (e.g. implemented using akka
persistence) is completely separated from the "query side". Akka Persistence Query itself is *not* directly the query
side of an application, however it can help to migrate data from the write side to the query side database. In very
simple scenarios Persistence Query may be powerful enough to fulfill the query needs of your app, however we highly
recommend (in the spirit of CQRS) of splitting up the write/read sides into separate datastores as the need arises.

## Dependencies

Akka persistence query is a separate jar file. Make sure that you have the following dependency in your project:

sbt
:   @@@vars
    ```
    "com.typesafe.akka" %% "akka-persistence-query" % "$akka.version$"
    ```
    @@@

Gradle
:   @@@vars
    ```
    compile group: 'com.typesafe.akka', name: 'akka-persistence-query_$scala.binary_version$', version: '$akka.version$'
    ```
    @@@

Maven
:   @@@vars
    ```
    <dependency>
      <groupId>com.typesafe.akka</groupId>
      <artifactId>akka-persistence-query_$scala.binary_version$</artifactId>
      <version>$akka.version$</version>
    </dependency>
    ```
    @@@

## Design overview

Akka persistence query is purposely designed to be a very loosely specified API.
This is in order to keep the provided APIs general enough for each journal implementation to be able to expose its best
features, e.g. a SQL journal can use complex SQL queries or if a journal is able to subscribe to a live event stream
this should also be possible to expose the same API - a typed stream of events.

**Each read journal must explicitly document which types of queries it supports.**
Refer to your journal's plugins documentation for details on which queries and semantics it supports.

While Akka Persistence Query does not provide actual implementations of ReadJournals, it defines a number of pre-defined
query types for the most common query scenarios, that most journals are likely to implement (however they are not required to).

## Read Journals

In order to issue queries one has to first obtain an instance of a `ReadJournal`.
Read journals are implemented as [Community plugins](http://akka.io/community/#plugins-to-akka-persistence-query), each targeting a specific datastore (for example Cassandra or JDBC
databases). For example, given a library that provides a `akka.persistence.query.my-read-journal` obtaining the related
journal is as simple as:

Scala
:  @@snip [PersistenceQueryDocSpec.scala]($code$/scala/docs/persistence/query/PersistenceQueryDocSpec.scala) { #basic-usage }

Java
:  @@snip [PersistenceQueryDocTest.java]($code$/java/jdocs/persistence/PersistenceQueryDocTest.java) { #basic-usage }

Journal implementers are encouraged to put this identifier in a variable known to the user, such that one can access it via
@scala[`readJournalFor[NoopJournal](NoopJournal.identifier)`]@java[`getJournalFor(NoopJournal.class, NoopJournal.identifier)`], however this is not enforced.

Read journal implementations are available as [Community plugins](http://akka.io/community/#plugins-to-akka-persistence-query).

### Predefined queries

Akka persistence query comes with a number of query interfaces built in and suggests Journal implementors to implement
them according to the semantics described below. It is important to notice that while these query types are very common
a journal is not obliged to implement all of them - for example because in a given journal such query would be
significantly inefficient.

@@@ note

Refer to the documentation of the `ReadJournal` plugin you are using for a specific list of supported query types.
For example, Journal plugins should document their stream completion strategies.

@@@

The predefined queries are:

#### AllPersistenceIdsQuery and CurrentPersistenceIdsQuery

`allPersistenceIds` which is designed to allow users to subscribe to a stream of all persistent ids in the system.
By default this stream should be assumed to be a "live" stream, which means that the journal should keep emitting new
persistence ids as they come into the system:

Scala
:  @@snip [PersistenceQueryDocSpec.scala]($code$/scala/docs/persistence/query/PersistenceQueryDocSpec.scala) { #all-persistence-ids-live }

Java
:  @@snip [PersistenceQueryDocTest.java]($code$/java/jdocs/persistence/PersistenceQueryDocTest.java) { #all-persistence-ids-live }

If your usage does not require a live stream, you can use the `currentPersistenceIds` query:

Scala
:  @@snip [PersistenceQueryDocSpec.scala]($code$/scala/docs/persistence/query/PersistenceQueryDocSpec.scala) { #all-persistence-ids-snap }

Java
:  @@snip [PersistenceQueryDocTest.java]($code$/java/jdocs/persistence/PersistenceQueryDocTest.java) { #all-persistence-ids-snap }

#### EventsByPersistenceIdQuery and CurrentEventsByPersistenceIdQuery

`eventsByPersistenceId` is a query equivalent to replaying a @ref:[PersistentActor](persistence.md#event-sourcing),
however, since it is a stream it is possible to keep it alive and watch for additional incoming events persisted by the
persistent actor identified by the given `persistenceId`.

Scala
:  @@snip [PersistenceQueryDocSpec.scala]($code$/scala/docs/persistence/query/PersistenceQueryDocSpec.scala) { #events-by-persistent-id }

Java
:  @@snip [PersistenceQueryDocTest.java]($code$/java/jdocs/persistence/PersistenceQueryDocTest.java) { #events-by-persistent-id }

Most journals will have to revert to polling in order to achieve this, 
which can typically be configured with a `refresh-interval` configuration property.

If your usage does not require a live stream, you can use the `currentEventsByPersistenceId` query.

#### EventsByTag and CurrentEventsByTag

`eventsByTag` allows querying events regardless of which `persistenceId` they are associated with. This query is hard to
implement in some journals or may need some additional preparation of the used data store to be executed efficiently.
The goal of this query is to allow querying for all events which are "tagged" with a specific tag.
That includes the use case to query all domain events of an Aggregate Root type.
Please refer to your read journal plugin's documentation to find out if and how it is supported.

Some journals may support tagging of events via an @ref:[Event Adapters](persistence.md#event-adapters) that wraps the events in a
`akka.persistence.journal.Tagged` with the given `tags`. The journal may support other ways of doing tagging - again,
how exactly this is implemented depends on the used journal. Here is an example of such a tagging event adapter:

Scala
:  @@snip [LeveldbPersistenceQueryDocSpec.scala]($code$/scala/docs/persistence/query/LeveldbPersistenceQueryDocSpec.scala) { #tagger }

Java
:  @@snip [LeveldbPersistenceQueryDocTest.java]($code$/java/jdocs/persistence/query/LeveldbPersistenceQueryDocTest.java) { #tagger }

@@@ note

A very important thing to keep in mind when using queries spanning multiple persistenceIds, such as `EventsByTag`
is that the order of events at which the events appear in the stream rarely is guaranteed (or stable between materializations).

Journals *may* choose to opt for strict ordering of the events, and should then document explicitly what kind of ordering
guarantee they provide - for example "*ordered by timestamp ascending, independently of persistenceId*" is easy to achieve
on relational databases, yet may be hard to implement efficiently on plain key-value datastores.

@@@

In the example below we query all events which have been tagged (we assume this was performed by the write-side using an
@ref:[EventAdapter](persistence.md#event-adapters), or that the journal is smart enough that it can figure out what we mean by this
tag - for example if the journal stored the events as json it may try to find those with the field `tag` set to this value etc.).

Scala
:  @@snip [PersistenceQueryDocSpec.scala]($code$/scala/docs/persistence/query/PersistenceQueryDocSpec.scala) { #events-by-tag }

Java
:  @@snip [PersistenceQueryDocTest.java]($code$/java/jdocs/persistence/PersistenceQueryDocTest.java) { #events-by-tag }

As you can see, we can use all the usual stream combinators available from @ref:[Streams](stream/index.md) on the resulting query stream,
including for example taking the first 10 and cancelling the stream. It is worth pointing out that the built-in `EventsByTag`
query has an optionally supported offset parameter (of type `Long`) which the journals can use to implement resumable-streams.
For example a journal may be able to use a WHERE clause to begin the read starting from a specific row, or in a datastore
that is able to order events by insertion time it could treat the Long as a timestamp and select only older events.

If your usage does not require a live stream, you can use the `currentEventsByTag` query.

### Materialized values of queries

Journals are able to provide additional information related to a query by exposing @ref:[Materialized values](stream/stream-quickstart.md#materialized-values-quick),
which are a feature of @ref:[Streams](stream/index.md) that allows to expose additional values at stream materialization time.

More advanced query journals may use this technique to expose information about the character of the materialized
stream, for example if it's finite or infinite, strictly ordered or not ordered at all. The materialized value type
is defined as the second type parameter of the returned `Source`, which allows journals to provide users with their
specialised query object, as demonstrated in the sample below:

Scala
:  @@snip [PersistenceQueryDocSpec.scala]($code$/scala/docs/persistence/query/PersistenceQueryDocSpec.scala) { #advanced-journal-query-types }

Java
:  @@snip [PersistenceQueryDocTest.java]($code$/java/jdocs/persistence/PersistenceQueryDocTest.java) { #advanced-journal-query-types }


Scala
:  @@snip [PersistenceQueryDocSpec.scala]($code$/scala/docs/persistence/query/PersistenceQueryDocSpec.scala) { #advanced-journal-query-definition }

Java
:  @@snip [PersistenceQueryDocTest.java]($code$/java/jdocs/persistence/PersistenceQueryDocTest.java) { #advanced-journal-query-definition }


Scala
:  @@snip [PersistenceQueryDocSpec.scala]($code$/scala/docs/persistence/query/PersistenceQueryDocSpec.scala) { #advanced-journal-query-usage }

Java
:  @@snip [PersistenceQueryDocTest.java]($code$/java/jdocs/persistence/PersistenceQueryDocTest.java) { #advanced-journal-query-usage }

## Performance and denormalization

When building systems using @ref:[Event sourcing](persistence.md#event-sourcing) and CQRS ([Command & Query Responsibility Segregation](https://msdn.microsoft.com/en-us/library/jj554200.aspx)) techniques
it is tremendously important to realise that the write-side has completely different needs from the read-side,
and separating those concerns into datastores that are optimised for either side makes it possible to offer the best
experience for the write and read sides independently.

For example, in a bidding system it is important to "take the write" and respond to the bidder that we have accepted
the bid as soon as possible, which means that write-throughput is of highest importance for the write-side – often this
means that data stores which are able to scale to accommodate these requirements have a less expressive query side.

On the other hand the same application may have some complex statistics view or we may have analysts working with the data
to figure out best bidding strategies and trends – this often requires some kind of expressive query capabilities like
for example SQL or writing Spark jobs to analyse the data. Therefore the data stored in the write-side needs to be
projected into the other read-optimised datastore.

@@@ note

When referring to **Materialized Views** in Akka Persistence think of it as "some persistent storage of the result of a Query".
In other words, it means that the view is created once, in order to be afterwards queried multiple times, as in this format
it may be more efficient or interesting to query it (instead of the source events directly).

@@@

### Materialize view to Reactive Streams compatible datastore

If the read datastore exposes a [Reactive Streams](http://reactive-streams.org) interface then implementing a simple projection
is as simple as, using the read-journal and feeding it into the databases driver interface, for example like so:

Scala
:  @@snip [PersistenceQueryDocSpec.scala]($code$/scala/docs/persistence/query/PersistenceQueryDocSpec.scala) { #projection-into-different-store-rs }

Java
:  @@snip [PersistenceQueryDocTest.java]($code$/java/jdocs/persistence/PersistenceQueryDocTest.java) { #projection-into-different-store-rs }

### Materialize view using mapAsync

If the target database does not provide a reactive streams `Subscriber` that can perform writes,
you may have to implement the write logic using plain functions or Actors instead.

In case your write logic is state-less and you just need to convert the events from one data type to another
before writing into the alternative datastore, then the projection is as simple as:

Scala
:  @@snip [PersistenceQueryDocSpec.scala]($code$/scala/docs/persistence/query/PersistenceQueryDocSpec.scala) { #projection-into-different-store-simple-classes }

Java
:  @@snip [PersistenceQueryDocTest.java]($code$/java/jdocs/persistence/PersistenceQueryDocTest.java) { #projection-into-different-store-simple-classes }


Scala
:  @@snip [PersistenceQueryDocSpec.scala]($code$/scala/docs/persistence/query/PersistenceQueryDocSpec.scala) { #projection-into-different-store-simple }

Java
:  @@snip [PersistenceQueryDocTest.java]($code$/java/jdocs/persistence/PersistenceQueryDocTest.java) { #projection-into-different-store-simple }

### Resumable projections

Sometimes you may need to implement "resumable" projections, that will not start from the beginning of time each time
when run. In this case you will need to store the sequence number (or `offset`) of the processed event and use it
the next time this projection is started. This pattern is not built-in, however is rather simple to implement yourself.

The example below additionally highlights how you would use Actors to implement the write side, in case
you need to do some complex logic that would be best handled inside an Actor before persisting the event
into the other datastore:

Scala
:  @@snip [PersistenceQueryDocSpec.scala]($code$/scala/docs/persistence/query/PersistenceQueryDocSpec.scala) { #projection-into-different-store-actor-run }

Java
:  @@snip [PersistenceQueryDocTest.java]($code$/java/jdocs/persistence/PersistenceQueryDocTest.java) { #projection-into-different-store-actor-run }


Scala
:  @@snip [PersistenceQueryDocSpec.scala]($code$/scala/docs/persistence/query/PersistenceQueryDocSpec.scala) { #projection-into-different-store-actor }

Java
:  @@snip [PersistenceQueryDocTest.java]($code$/java/jdocs/persistence/PersistenceQueryDocTest.java) { #projection-into-different-store-actor }

<a id="read-journal-plugin-api"></a>
## Query plugins

Query plugins are various (mostly community driven) `ReadJournal` implementations for all kinds
of available datastores. The complete list of available plugins is maintained on the Akka Persistence Query [Community Plugins](http://akka.io/community/#plugins-to-akka-persistence-query) page.

The plugin for LevelDB is described in @ref:[Persistence Query for LevelDB](persistence-query-leveldb.md).

This section aims to provide tips and guide plugin developers through implementing a custom query plugin.
Most users will not need to implement journals themselves, except if targeting a not yet supported datastore.

@@@ note

Since different data stores provide different query capabilities journal plugins **must extensively document**
their exposed semantics as well as handled query scenarios.

@@@

### ReadJournal plugin API

A read journal plugin must implement `akka.persistence.query.ReadJournalProvider` which
creates instances of `akka.persistence.query.scaladsl.ReadJournal` and
`akka.persistence.query.javaadsl.ReadJournal`. The plugin must implement both the `scaladsl`
and the `javadsl` @scala[traits]@java[interfaces] because the `akka.stream.scaladsl.Source` and 
`akka.stream.javadsl.Source` are different types and even though those types can easily be converted
to each other it is most convenient for the end user to get access to the Java or Scala `Source` directly.
As illustrated below one of the implementations can delegate to the other. 

Below is a simple journal implementation:

Scala
:  @@snip [PersistenceQueryDocSpec.scala]($code$/scala/docs/persistence/query/PersistenceQueryDocSpec.scala) { #my-read-journal }

Java
:  @@snip [PersistenceQueryDocTest.java]($code$/java/jdocs/persistence/PersistenceQueryDocTest.java) { #my-read-journal }

And the `eventsByTag` could be backed by such an Actor for example:

Scala
:  @@snip [MyEventsByTagPublisher.scala]($code$/scala/docs/persistence/query/MyEventsByTagPublisher.scala) { #events-by-tag-publisher }

Java
:  @@snip [MyEventsByTagJavaPublisher.java]($code$/java/jdocs/persistence/query/MyEventsByTagJavaPublisher.java) { #events-by-tag-publisher }

The `ReadJournalProvider` class must have a constructor with one of these signatures:

 * constructor with a `ExtendedActorSystem` parameter, a `com.typesafe.config.Config` parameter, and a `String` parameter for the config path
 * constructor with a `ExtendedActorSystem` parameter, and a `com.typesafe.config.Config` parameter
 * constructor with one `ExtendedActorSystem` parameter
 * constructor without parameters

The plugin section of the actor system's config will be passed in the config constructor parameter. The config path
of the plugin is passed in the `String` parameter.

If the underlying datastore only supports queries that are completed when they reach the
end of the "result set", the journal has to submit new queries after a while in order
to support "infinite" event streams that include events stored after the initial query
has completed. It is recommended that the plugin use a configuration property named
`refresh-interval` for defining such a refresh interval. 

### Plugin TCK

TODO, not available yet.
