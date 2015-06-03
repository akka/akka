/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package doc;

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
import scala.concurrent.Future;
import akka.japi.Option;
import akka.japi.Procedure;


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
    public Future<Option<SelectedSnapshot>> doLoadAsync(String persistenceId, SnapshotSelectionCriteria criteria) {
      return null;
    }

    @Override
    public Future<Void> doSaveAsync(SnapshotMetadata metadata, Object snapshot) {
      return null;
    }

    @Override
    public void onSaved(SnapshotMetadata metadata) throws Exception {
    }

    @Override
    public Future<Void> doDelete(SnapshotMetadata metadata) throws Exception {
      return Futures.successful(null);
    }

    @Override
    public Future<Void> doDelete(String persistenceId, SnapshotSelectionCriteria criteria) throws Exception {
      return Futures.successful(null);
    }
  }

  class MyAsyncJournal extends AsyncWriteJournal {
    @Override
    public Future<Void> doAsyncWriteMessages(Iterable<PersistentRepr> messages) {
      return null;
    }

    @Override
    public Future<Void> doAsyncDeleteMessagesTo(String persistenceId, long toSequenceNr, boolean permanent) {
      return null;
    }

    @Override
    public Future<Void> doAsyncReplayMessages(String persistenceId, long fromSequenceNr,
                                              long toSequenceNr,
                                              long max,
                                              Procedure<PersistentRepr> replayCallback) {
      return null;
    }

    @Override
    public Future<Long> doAsyncReadHighestSequenceNr(String persistenceId, long fromSequenceNr) {
      return null;
    }
  }
}
