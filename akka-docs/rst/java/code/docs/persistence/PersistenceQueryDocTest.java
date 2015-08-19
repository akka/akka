/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.persistence;

import static akka.pattern.Patterns.ask;

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
import java.util.concurrent.TimeUnit;

public class PersistenceQueryDocTest {

  final ActorSystem system = ActorSystem.create();
  final ActorMaterializer mat = ActorMaterializer.create(system);

  //#my-read-journal
  class MyReadJournal implements ReadJournal {
    private final ExtendedActorSystem system;

    public MyReadJournal(ExtendedActorSystem system, Config config) {
      this.system = system;
    }

    final FiniteDuration defaultRefreshInterval = FiniteDuration.create(3, TimeUnit.SECONDS);

    @SuppressWarnings("unchecked")
    public <T, M> Source<T, M> query(Query<T, M> q, Hint... hints) {
      if (q instanceof EventsByTag) {
        final EventsByTag eventsByTag = (EventsByTag) q;
        final String tag = eventsByTag.tag();
        long offset = eventsByTag.offset();

        final Props props = MyEventsByTagPublisher.props(tag, offset, refreshInterval(hints));

        return (Source<T, M>) Source.<EventEnvelope>actorPublisher(props)
                                    .mapMaterializedValue(noMaterializedValue());
      } else {
        // unsuported
        return Source.<T>failed(
          new UnsupportedOperationException(
            "Query " + q + " not supported by " + getClass().getName()))
                     .mapMaterializedValue(noMaterializedValue());
      }
    }

    private FiniteDuration refreshInterval(Hint[] hints) {
      for (Hint hint : hints)
        if (hint instanceof RefreshInterval)
          return ((RefreshInterval) hint).interval();

      return defaultRefreshInterval;
    }

    private <I, M> akka.japi.function.Function<I, M> noMaterializedValue() {
      return param -> (M) null;
    }
  }
  //#my-read-journal

  void demonstrateBasicUsage() {
    final ActorSystem system = ActorSystem.create();

    //#basic-usage
    // obtain read journal by plugin id
    final ReadJournal readJournal =
      PersistenceQuery.get(system)
                      .getReadJournalFor("akka.persistence.query.noop-read-journal");

    // issue query to journal
    Source<EventEnvelope, BoxedUnit> source =
      readJournal.query(EventsByPersistenceId.create("user-1337", 0, Long.MAX_VALUE));

    // materialize stream, consuming events
    ActorMaterializer mat = ActorMaterializer.create(system);
    source.runForeach(event -> System.out.println("Event: " + event), mat);
    //#basic-usage
  }

  void demonstrateAllPersistenceIdsLive() {
    final ReadJournal readJournal =
      PersistenceQuery.get(system)
                      .getReadJournalFor("akka.persistence.query.noop-read-journal");

    //#all-persistence-ids-live
    readJournal.query(AllPersistenceIds.getInstance());
    //#all-persistence-ids-live
  }

  void demonstrateNoRefresh() {
    final ActorSystem system = ActorSystem.create();

    final ReadJournal readJournal =
      PersistenceQuery.get(system)
                      .getReadJournalFor("akka.persistence.query.noop-read-journal");

    //#all-persistence-ids-snap
    readJournal.query(AllPersistenceIds.getInstance(), NoRefresh.getInstance());
    //#all-persistence-ids-snap
  }

  void demonstrateRefresh() {
    final ActorSystem system = ActorSystem.create();

    final ReadJournal readJournal =
      PersistenceQuery.get(system)
                      .getReadJournalFor("akka.persistence.query.noop-read-journal");

    //#events-by-persistent-id-refresh
    final RefreshInterval refresh = RefreshInterval.create(1, TimeUnit.SECONDS);
    readJournal.query(EventsByPersistenceId.create("user-us-1337"), refresh);
    //#events-by-persistent-id-refresh
  }

  void demonstrateEventsByTag() {
    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer mat = ActorMaterializer.create(system);

    final ReadJournal readJournal =
      PersistenceQuery.get(system)
                      .getReadJournalFor("akka.persistence.query.noop-read-journal");

    //#events-by-tag
    // assuming journal is able to work with numeric offsets we can:
    final Source<EventEnvelope, BoxedUnit> blueThings =
      readJournal.query(EventsByTag.create("blue"));

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
    Source<EventEnvelope, BoxedUnit> blue = readJournal.query(EventsByTag.create("blue", 10));
    //#events-by-tag
  }
  //#materialized-query-metadata-classes
  // a plugin can provide:

  //#materialized-query-metadata-classes

  static
  //#materialized-query-metadata-classes
  final class QueryMetadata {
    public final boolean deterministicOrder;
    public final boolean infinite;

    public QueryMetadata(Boolean deterministicOrder, Boolean infinite) {
      this.deterministicOrder = deterministicOrder;
      this.infinite = infinite;
    }
  }

  //#materialized-query-metadata-classes

  static
  //#materialized-query-metadata-classes
  final class AllEvents implements Query<Object, QueryMetadata> {
    private AllEvents() {}
    private static AllEvents INSTANCE = new AllEvents();
  }

  //#materialized-query-metadata-classes

  void demonstrateMaterializedQueryValues() {
    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer mat = ActorMaterializer.create(system);

    final ReadJournal readJournal =
      PersistenceQuery.get(system)
                      .getReadJournalFor("akka.persistence.query.noop-read-journal");

    //#materialized-query-metadata

    final Source<Object, QueryMetadata> events = readJournal.query(AllEvents.INSTANCE);

    events.mapMaterializedValue(meta -> {
      System.out.println("The query is: " +
        "ordered deterministically: " + meta.deterministicOrder + " " +
        "infinite: " + meta.infinite);
      return meta;
    });
    //#materialized-query-metadata
  }

  class ReactiveStreamsCompatibleDBDriver {
    Subscriber<List<Object>> batchWriter() {
      return null;
    }
  }

  void demonstrateWritingIntoDifferentStore() {
    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer mat = ActorMaterializer.create(system);

    final ReadJournal readJournal =
      PersistenceQuery.get(system)
                      .getReadJournalFor("akka.persistence.query.noop-read-journal");


    //#projection-into-different-store-rs
    final ReactiveStreamsCompatibleDBDriver driver = new ReactiveStreamsCompatibleDBDriver();
    final Subscriber<List<Object>> dbBatchWriter = driver.batchWriter();

    // Using an example (Reactive Streams) Database driver
    readJournal
      .query(EventsByPersistenceId.create("user-1337"))
      .map(envelope -> envelope.event())
      .grouped(20) // batch inserts into groups of 20
      .runWith(Sink.create(dbBatchWriter), mat); // write batches to read-side database
    //#projection-into-different-store-rs
  }

  //#projection-into-different-store-simple-classes
  class ExampleStore {
    Future<Void> save(Object any) {
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

    final ReadJournal readJournal =
      PersistenceQuery.get(system)
                      .getReadJournalFor("akka.persistence.query.noop-read-journal");


    //#projection-into-different-store-simple
    final ExampleStore store = new ExampleStore();

    readJournal
      .query(EventsByTag.create("bid"))
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

    public Future<Long> saveProgress(long offset) {
      // ...
      //#projection-into-different-store
      return null;
      //#projection-into-different-store
    }
    public Future<Long> latestOffset() {
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

    final ReadJournal readJournal =
      PersistenceQuery.get(system)
                      .getReadJournalFor("akka.persistence.query.noop-read-journal");


    //#projection-into-different-store-actor-run
    final Timeout timeout = Timeout.apply(3, TimeUnit.SECONDS);

    final MyResumableProjection bidProjection = new MyResumableProjection("bid");

    final Props writerProps = Props.create(TheOneWhoWritesToQueryJournal.class, "bid");
    final ActorRef writer = system.actorOf(writerProps, "bid-projection-writer");

    long startFromOffset = Await.result(bidProjection.latestOffset(), timeout.duration());

    readJournal
      .query(EventsByTag.create("bid", startFromOffset))
      .<Long>mapAsync(8, envelope -> {
        final Future<Object> f = ask(writer, envelope.event(), timeout);
        return f.<Long>map(new Mapper<Object, Long>() {
          @Override public Long apply(Object in) {
            return envelope.offset();
          }
        }, system.dispatcher());
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
