/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.persistence;

//#plugin-imports
import akka.dispatch.Futures;
import akka.persistence.*;
import akka.persistence.journal.japi.*;
import akka.persistence.snapshot.japi.*;
//#plugin-imports

import akka.actor.*;
import akka.persistence.journal.leveldb.SharedLeveldbJournal;
import akka.persistence.journal.leveldb.SharedLeveldbStore;
import akka.japi.pf.ReceiveBuilder;
import java.util.ArrayList;
import scala.concurrent.Future;
import java.util.function.Consumer;
import java.util.Optional;


public class LambdaPersistencePluginDocTest {


  static Object o1 = new Object() {
    final ActorSystem system = null;
    //#shared-store-creation
    final ActorRef store = system.actorOf(Props.create(SharedLeveldbStore.class), "store");
    //#shared-store-creation

    //#shared-store-usage
    class SharedStorageUsage extends AbstractActor {
      @Override
      public void preStart() throws Exception {
        String path = "akka.tcp://example@127.0.0.1:2552/user/store";
        ActorSelection selection = context().actorSelection(path);
        selection.tell(new Identify(1), self());
      }

      public SharedStorageUsage() {
        receive(ReceiveBuilder.
          match(ActorIdentity.class, ai -> {
            if (ai.correlationId().equals(1)) {
              ActorRef store = ai.getRef();
              if (store != null) {
                SharedLeveldbJournal.setStore(store, context().system());
              }
            }
          }).build()
        );
      }
    }
    //#shared-store-usage
  };

  class MySnapshotStore extends SnapshotStore {
    @Override
    public Future<Optional<SelectedSnapshot>> doLoadAsync(String persistenceId, SnapshotSelectionCriteria criteria) {
      return null;
    }

    @Override
    public Future<Void> doSaveAsync(SnapshotMetadata metadata, Object snapshot) {
      return null;
    }

    @Override
    public Future<Void> doDeleteAsync(SnapshotMetadata metadata) {
      return Futures.successful(null);
    }

    @Override
    public Future<Void> doDeleteAsync(String persistenceId, SnapshotSelectionCriteria criteria) {
      return Futures.successful(null);
    }
  }

  class MyAsyncJournal extends AsyncWriteJournal {
    //#sync-journal-plugin-api
    @Override
    public Future<Iterable<Optional<Exception>>> doAsyncWriteMessages(
        Iterable<AtomicWrite> messages) {
      try {
        Iterable<Optional<Exception>> result = new ArrayList<Optional<Exception>>();
        // blocking call here...
        // result.add(..)
        return Futures.successful(result);
      } catch (Exception e) {
        return Futures.failed(e);
      }
    }
    //#sync-journal-plugin-api

    @Override
    public Future<Void> doAsyncDeleteMessagesTo(String persistenceId, long toSequenceNr) {
      return null;
    }

    @Override
    public Future<Void> doAsyncReplayMessages(String persistenceId, long fromSequenceNr,
                                              long toSequenceNr,
                                              long max,
                                              Consumer<PersistentRepr> replayCallback) {
      return null;
    }

    @Override
    public Future<Long> doAsyncReadHighestSequenceNr(String persistenceId, long fromSequenceNr) {
      return null;
    }
  }
}
