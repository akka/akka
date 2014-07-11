/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.persistence;

//#plugin-imports

import akka.actor.*;
import akka.japi.Option;
import akka.japi.Procedure;
import akka.persistence.*;
import akka.persistence.japi.journal.JavaJournalSpec;
import akka.persistence.japi.snapshot.JavaSnapshotStoreSpec;
import akka.persistence.journal.japi.AsyncWriteJournal;
import akka.persistence.journal.leveldb.SharedLeveldbJournal;
import akka.persistence.journal.leveldb.SharedLeveldbStore;
import akka.persistence.snapshot.japi.SnapshotStore;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.iq80.leveldb.util.FileUtils;
import scala.concurrent.Future;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

//#plugin-imports

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
        public void doDelete(SnapshotMetadata metadata) throws Exception {
        }

        @Override
        public void doDelete(String persistenceId, SnapshotSelectionCriteria criteria) throws Exception {
        }
    }

    class MyAsyncJournal extends AsyncWriteJournal {
        @Override
        public Future<Void> doAsyncWriteMessages(Iterable<PersistentRepr> messages) {
            return null;
        }

        @Override
        public Future<Void> doAsyncWriteConfirmations(Iterable<PersistentConfirmation> confirmations) {
            return null;
        }

        @Override
        public Future<Void> doAsyncDeleteMessages(Iterable<PersistentId> messageIds, boolean permanent) {
            return null;
        }

        @Override
        public Future<Void> doAsyncDeleteMessagesTo(String persistenceId, long toSequenceNr, boolean permanent) {
            return null;
        }

        @Override
        public Future<Void> doAsyncReplayMessages(String persistenceId, long fromSequenceNr, long toSequenceNr, long max, Procedure<PersistentRepr> replayCallback) {
            return null;
        }

        @Override
        public Future<Long> doAsyncReadHighestSequenceNr(String persistenceId, long fromSequenceNr) {
            return null;
        }
    }


    static Object o2 = new Object() {
        //#journal-tck-java
        class MyJournalSpecTest extends JavaJournalSpec {

            public MyJournalSpecTest() {
                super(ConfigFactory.parseString(
                        "persistence.journal.plugin = " +
                        "\"akka.persistence.journal.leveldb-shared\""));
            }
        }
        //#journal-tck-java
    };

    static Object o3 = new Object() {
        //#snapshot-store-tck-java
        class MySnapshotStoreTest extends JavaSnapshotStoreSpec {

            public MySnapshotStoreTest() {
                super(ConfigFactory.parseString(
                        "akka.persistence.snapshot-store.plugin = " +
                        "\"akka.persistence.snapshot-store.local\""));
            }
        }
        //#snapshot-store-tck-java
    };

    static Object o4 = new Object() {
        //#journal-tck-before-after-java
        class MyJournalSpecTest extends JavaJournalSpec {

            List<File> storageLocations = new ArrayList<File>();

            public MyJournalSpecTest() {
                super(ConfigFactory.parseString(
                        "persistence.journal.plugin = " +
                        "\"akka.persistence.journal.leveldb-shared\""));

                Config config = system().settings().config();
                storageLocations.add(new File(
                    config.getString("akka.persistence.journal.leveldb.dir")));
                storageLocations.add(new File(
                    config.getString("akka.persistence.snapshot-store.local.dir")));
            }

            @Override
            public void beforeAll() {
                for (File storageLocation : storageLocations) {
                    FileUtils.deleteRecursively(storageLocation);
                }
                super.beforeAll();
            }

            @Override
            public void afterAll() {
                super.afterAll();
                for (File storageLocation : storageLocations) {
                    FileUtils.deleteRecursively(storageLocation);
                }
            }
        }
        //#journal-tck-before-after-java
    };
}
