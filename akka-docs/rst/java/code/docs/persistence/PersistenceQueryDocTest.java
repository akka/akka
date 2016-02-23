/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.persistence;

import static akka.pattern.PatternsCS.ask;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator;

import akka.NotUsed;
import com.typesafe.config.Config;

import akka.actor.*;
import akka.dispatch.Mapper;
import akka.event.EventStreamSpec;
import akka.japi.Function;
import akka.japi.Procedure;
import akka.japi.pf.ReceiveBuilder;
import akka.pattern.BackoffSupervisor;
import akka.persistence.*;
import akka.persistence.query.*;
import akka.persistence.query.javadsl.ReadJournal;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.util.Timeout;

import docs.persistence.query.MyEventsByTagPublisher;
import docs.persistence.query.PersistenceQueryDocSpec;
import org.reactivestreams.Subscriber;
import scala.collection.Seq;
import scala.collection.immutable.Vector;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.Boxed;
import scala.runtime.BoxedUnit;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class PersistenceQueryDocTest {

  final ActorSystem system = ActorSystem.create();
  final ActorMaterializer mat = ActorMaterializer.create(system);
  
  static
  //#advanced-journal-query-types
  public class RichEvent {
    public final Set<String >tags;
    public final Object payload;
    
    public RichEvent(Set<String> tags, Object payload) {
      this.tags = tags;
      this.payload = payload;
    }
  }
  //#advanced-journal-query-types

  static
  //#advanced-journal-query-types
  // a plugin can provide:
  public final class QueryMetadata{
    public final boolean deterministicOrder;
    public final boolean infinite;

    public QueryMetadata(boolean deterministicOrder, boolean infinite) {
      this.deterministicOrder = deterministicOrder;
      this.infinite = infinite;
    }
  }
  //#advanced-journal-query-types

  static
  //#my-read-journal
  public class MyReadJournalProvider implements ReadJournalProvider {
    private final MyJavadslReadJournal javadslReadJournal;

    public MyReadJournalProvider(ExtendedActorSystem system, Config config) {
      this.javadslReadJournal = new MyJavadslReadJournal(system, config);
    }
    
    @Override
    public MyScaladslReadJournal scaladslReadJournal() {
      return new MyScaladslReadJournal(javadslReadJournal);
    }

    @Override
    public MyJavadslReadJournal javadslReadJournal() {
      return this.javadslReadJournal;
    }  
  }
  //#my-read-journal
  
  static
  //#my-read-journal
  public class MyJavadslReadJournal implements 
  akka.persistence.query.javadsl.ReadJournal,
  akka.persistence.query.javadsl.EventsByTagQuery,
  akka.persistence.query.javadsl.EventsByPersistenceIdQuery,
  akka.persistence.query.javadsl.AllPersistenceIdsQuery,
  akka.persistence.query.javadsl.CurrentPersistenceIdsQuery {
    
    private final FiniteDuration refreshInterval;

    public MyJavadslReadJournal(ExtendedActorSystem system, Config config) {
      refreshInterval = 
          FiniteDuration.create(config.getDuration("refresh-interval", 
              TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS);
    }

    @Override
    public Source<EventEnvelope, NotUsed> eventsByTag(String tag, long offset) {
      final Props props = MyEventsByTagPublisher.props(tag, offset, refreshInterval);
      return Source.<EventEnvelope>actorPublisher(props).
          mapMaterializedValue(m -> NotUsed.getInstance());
    }

    @Override
    public Source<EventEnvelope, NotUsed> eventsByPersistenceId(String persistenceId,
        long fromSequenceNr, long toSequenceNr) {
      // implement in a similar way as eventsByTag
      throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public Source<String, NotUsed> allPersistenceIds() {
      // implement in a similar way as eventsByTag
      throw new UnsupportedOperationException("Not implemented yet");
    }
    
    @Override
    public Source<String, NotUsed> currentPersistenceIds() {
      // implement in a similar way as eventsByTag
      throw new UnsupportedOperationException("Not implemented yet");
    }
    
    // possibility to add more plugin specific queries

    //#advanced-journal-query-definition
    public Source<RichEvent, QueryMetadata> byTagsWithMeta(Set<String> tags) {
      //#advanced-journal-query-definition
      // implement in a similar way as eventsByTag
      throw new UnsupportedOperationException("Not implemented yet");
    }

  }
  //#my-read-journal
  
  static
  //#my-read-journal
  public class MyScaladslReadJournal implements 
  akka.persistence.query.scaladsl.ReadJournal,
  akka.persistence.query.scaladsl.EventsByTagQuery,
  akka.persistence.query.scaladsl.EventsByPersistenceIdQuery,
  akka.persistence.query.scaladsl.AllPersistenceIdsQuery,
  akka.persistence.query.scaladsl.CurrentPersistenceIdsQuery {
    
    private final MyJavadslReadJournal javadslReadJournal;

    public MyScaladslReadJournal(MyJavadslReadJournal javadslReadJournal) {
      this.javadslReadJournal = javadslReadJournal;
    }

    @Override
    public akka.stream.scaladsl.Source<EventEnvelope, NotUsed> eventsByTag(
        String tag, long offset) {
      return javadslReadJournal.eventsByTag(tag, offset).asScala();
    }

    @Override
    public akka.stream.scaladsl.Source<EventEnvelope, NotUsed> eventsByPersistenceId(
        String persistenceId, long fromSequenceNr, long toSequenceNr) {
      return javadslReadJournal.eventsByPersistenceId(persistenceId, fromSequenceNr, 
          toSequenceNr).asScala();
    }

    @Override
    public akka.stream.scaladsl.Source<String, NotUsed> allPersistenceIds() {
      return javadslReadJournal.allPersistenceIds().asScala();
    }
    
    @Override
    public akka.stream.scaladsl.Source<String, NotUsed> currentPersistenceIds() {
      return javadslReadJournal.currentPersistenceIds().asScala();
    }
    
    // possibility to add more plugin specific queries

    public akka.stream.scaladsl.Source<RichEvent, QueryMetadata> byTagsWithMeta(
        scala.collection.Set<String> tags) {
      Set<String> jTags = scala.collection.JavaConversions.setAsJavaSet(tags);
      return javadslReadJournal.byTagsWithMeta(jTags).asScala();
    }

  }
  //#my-read-journal

  void demonstrateBasicUsage() {
    final ActorSystem system = ActorSystem.create();

    //#basic-usage
    // obtain read journal by plugin id
    final MyJavadslReadJournal readJournal =
      PersistenceQuery.get(system).getReadJournalFor(MyJavadslReadJournal.class,
          "akka.persistence.query.my-read-journal");

    // issue query to journal
    Source<EventEnvelope, NotUsed> source =
      readJournal.eventsByPersistenceId("user-1337", 0, Long.MAX_VALUE);

    // materialize stream, consuming events
    ActorMaterializer mat = ActorMaterializer.create(system);
    source.runForeach(event -> System.out.println("Event: " + event), mat);
    //#basic-usage
  }

  void demonstrateAllPersistenceIdsLive() {
    final MyJavadslReadJournal readJournal =
        PersistenceQuery.get(system).getReadJournalFor(MyJavadslReadJournal.class,
            "akka.persistence.query.my-read-journal");

    //#all-persistence-ids-live
    readJournal.allPersistenceIds();
    //#all-persistence-ids-live
  }

  void demonstrateNoRefresh() {
    final ActorSystem system = ActorSystem.create();

    final MyJavadslReadJournal readJournal =
        PersistenceQuery.get(system).getReadJournalFor(MyJavadslReadJournal.class,
            "akka.persistence.query.my-read-journal");

    //#all-persistence-ids-snap
    readJournal.currentPersistenceIds();
    //#all-persistence-ids-snap
  }

  void demonstrateRefresh() {
    final ActorSystem system = ActorSystem.create();

    final MyJavadslReadJournal readJournal =
        PersistenceQuery.get(system).getReadJournalFor(MyJavadslReadJournal.class,
            "akka.persistence.query.my-read-journal");

    //#events-by-persistent-id
    readJournal.eventsByPersistenceId("user-us-1337", 0L, Long.MAX_VALUE);
    //#events-by-persistent-id
  }

  void demonstrateEventsByTag() {
    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer mat = ActorMaterializer.create(system);

    final MyJavadslReadJournal readJournal =
        PersistenceQuery.get(system).getReadJournalFor(MyJavadslReadJournal.class,
            "akka.persistence.query.my-read-journal");

    //#events-by-tag
    // assuming journal is able to work with numeric offsets we can:
    final Source<EventEnvelope, NotUsed> blueThings =
      readJournal.eventsByTag("blue", 0L);

    // find top 10 blue things:
    final Future<List<Object>> top10BlueThings =
      (Future<List<Object>>) blueThings
        .map(t -> t.event())
        .take(10) // cancels the query stream after pulling 10 elements
        .<List<Object>>runFold(new ArrayList<>(10), (acc, e) -> {
          acc.add(e);
          return acc;
        }, mat);

    // start another query, from the known offset
    Source<EventEnvelope, NotUsed> blue = readJournal.eventsByTag("blue", 10);
    //#events-by-tag
  }


  void demonstrateMaterializedQueryValues() {
    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer mat = ActorMaterializer.create(system);

    final MyJavadslReadJournal readJournal =
        PersistenceQuery.get(system).getReadJournalFor(MyJavadslReadJournal.class,
            "akka.persistence.query.my-read-journal");

    //#advanced-journal-query-usage

    Set<String> tags = new HashSet<String>();
    tags.add("red");
    tags.add("blue");
    final Source<RichEvent, QueryMetadata> events = readJournal.byTagsWithMeta(tags)
      .mapMaterializedValue(meta -> {
        System.out.println("The query is: " +
          "ordered deterministically: " + meta.deterministicOrder + " " +
          "infinite: " + meta.infinite);
        return meta;
      });
    
    events.map(event -> {
      System.out.println("Event payload: " + event.payload); 
      return event.payload;
    }).runWith(Sink.ignore(), mat);
    
    
    //#advanced-journal-query-usage
  }

  class ReactiveStreamsCompatibleDBDriver {
    Subscriber<List<Object>> batchWriter() {
      return null;
    }
  }

  void demonstrateWritingIntoDifferentStore() {
    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer mat = ActorMaterializer.create(system);

    final MyJavadslReadJournal readJournal =
        PersistenceQuery.get(system).getReadJournalFor(MyJavadslReadJournal.class,
            "akka.persistence.query.my-read-journal");


    //#projection-into-different-store-rs
    final ReactiveStreamsCompatibleDBDriver driver = new ReactiveStreamsCompatibleDBDriver();
    final Subscriber<List<Object>> dbBatchWriter = driver.batchWriter();

    // Using an example (Reactive Streams) Database driver
    readJournal
      .eventsByPersistenceId("user-1337", 0L, Long.MAX_VALUE)
      .map(envelope -> envelope.event())
      .grouped(20) // batch inserts into groups of 20
      .runWith(Sink.fromSubscriber(dbBatchWriter), mat); // write batches to read-side database
    //#projection-into-different-store-rs
  }

  //#projection-into-different-store-simple-classes
  class ExampleStore {
    CompletionStage<Void> save(Object any) {
      // ...
      //#projection-into-different-store-simple-classes
      return null;
      //#projection-into-different-store-simple-classes
    }
  }
  //#projection-into-different-store-simple-classes

  void demonstrateWritingIntoDifferentStoreWithMapAsync() {
    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer mat = ActorMaterializer.create(system);

    final MyJavadslReadJournal readJournal =
        PersistenceQuery.get(system).getReadJournalFor(MyJavadslReadJournal.class,
            "akka.persistence.query.my-read-journal");


    //#projection-into-different-store-simple
    final ExampleStore store = new ExampleStore();

    readJournal
      .eventsByTag("bid", 0L)
      .mapAsync(1, store::save)
      .runWith(Sink.ignore(), mat);
    //#projection-into-different-store-simple
  }

  //#projection-into-different-store
  class MyResumableProjection {
    private final String name;

    public MyResumableProjection(String name) {
      this.name = name;
    }

    public CompletionStage<Long> saveProgress(long offset) {
      // ...
      //#projection-into-different-store
      return null;
      //#projection-into-different-store
    }
    public CompletionStage<Long> latestOffset() {
      // ...
      //#projection-into-different-store
      return null;
      //#projection-into-different-store
    }
  }
  //#projection-into-different-store


  void demonstrateWritingIntoDifferentStoreWithResumableProjections() throws Exception {
    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer mat = ActorMaterializer.create(system);

    final MyJavadslReadJournal readJournal =
        PersistenceQuery.get(system).getReadJournalFor(MyJavadslReadJournal.class,
            "akka.persistence.query.my-read-journal");


    //#projection-into-different-store-actor-run
    final Timeout timeout = Timeout.apply(3, TimeUnit.SECONDS);

    final MyResumableProjection bidProjection = new MyResumableProjection("bid");

    final Props writerProps = Props.create(TheOneWhoWritesToQueryJournal.class, "bid");
    final ActorRef writer = system.actorOf(writerProps, "bid-projection-writer");

    long startFromOffset = bidProjection.latestOffset().toCompletableFuture().get(3, TimeUnit.SECONDS);

    readJournal
      .eventsByTag("bid", startFromOffset)
      .mapAsync(8, envelope -> {
        final CompletionStage<Object> f = ask(writer, envelope.event(), timeout);
        return f.thenApplyAsync(in -> envelope.offset(), system.dispatcher());
      })
      .mapAsync(1, offset -> bidProjection.saveProgress(offset))
      .runWith(Sink.ignore(), mat);
  }

  //#projection-into-different-store-actor-run

  class ComplexState {

    boolean readyToSave() {
      return false;
    }
  }

  static class Record {
    static Record of(Object any) {
      return new Record();
    }
  }

  //#projection-into-different-store-actor
  final class TheOneWhoWritesToQueryJournal extends AbstractActor {
    private final ExampleStore store;

    private ComplexState state = new ComplexState();

    public TheOneWhoWritesToQueryJournal() {
      store = new ExampleStore();

      receive(ReceiveBuilder.matchAny(message -> {
        state = updateState(state, message);

        // example saving logic that requires state to become ready:
        if (state.readyToSave())
          store.save(Record.of(state));
        
      }).build());
    }


    ComplexState updateState(ComplexState state, Object msg) {
      // some complicated aggregation logic here ...
      return state;
    }
  }
  //#projection-into-different-store-actor

}
