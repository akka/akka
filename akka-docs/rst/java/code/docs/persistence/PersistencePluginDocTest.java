/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.persistence;

//#plugin-imports
import akka.actor.UntypedActor;
import scala.concurrent.Future;
import akka.japi.Option;
import akka.japi.Procedure;
import akka.persistence.*;
import akka.persistence.journal.japi.*;
import akka.persistence.snapshot.japi.*;
//#plugin-imports
import akka.actor.*;
import akka.persistence.journal.leveldb.SharedLeveldbJournal;
import akka.persistence.journal.leveldb.SharedLeveldbStore;

public class PersistencePluginDocTest {


    static Object o1 = new Object() {
        final ActorSystem system = null;
        //#shared-store-creation
        final ActorRef store = system.actorOf(Props.create(SharedLeveldbStore.class), "store");
        //#shared-store-creation

        //#shared-store-usage
        class SharedStorageUsage extends UntypedActor {
            @Override
            public void preStart() throws Exception {
                String path = "akka.tcp://example@127.0.0.1:2552/user/store";
                ActorSelection selection = getContext().actorSelection(path);
                selection.tell(new Identify(1), getSelf());
            }

            @Override
            public void onReceive(Object message) throws Exception {
                if (message instanceof ActorIdentity) {
                    ActorIdentity identity = (ActorIdentity) message;
                    if (identity.correlationId().equals(1)) {
                        ActorRef store = identity.getRef();
                        if (store != null) {
                            SharedLeveldbJournal.setStore(store, getContext().system());
                        }
                    }
                }
            }
        }
        //#shared-store-usage
    };

    class MySnapshotStore extends SnapshotStore {
        @Override
        public Future<Option<SelectedSnapshot>> doLoadAsync(String processorId, SnapshotSelectionCriteria criteria) {
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
        public void doDelete(SnapshotMetadata metadata) throws Exception {
        }

        @Override
        public void doDelete(String processorId, SnapshotSelectionCriteria criteria) throws Exception {
        }
    }

    class MyAsyncJournal extends AsyncWriteJournal {
        @Override
        public Future<Long> doReplayAsync(String processorId, long fromSequenceNr, long toSequenceNr, Procedure<PersistentRepr> replayCallback) {
            return null;
        }

        @Override
        public Future<Void> doWriteAsync(Iterable<PersistentRepr> persistentBatch) {
            return null;
        }

        @Override
        public Future<Void> doDeleteAsync(String processorId, long fromSequenceNr, long toSequenceNr, boolean permanent) {
            return null;
        }

        @Override
        public Future<Void> doConfirmAsync(String processorId, long sequenceNr, String channelId) {
            return null;
        }
    }
}
