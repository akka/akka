/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.persistence;

//#plugin-imports
import scala.concurrent.Future;
import akka.japi.Option;
import akka.japi.Procedure;
import akka.persistence.*;
import akka.persistence.journal.japi.*;
import akka.persistence.snapshot.japi.*;
//#plugin-imports

public class PersistencePluginDocTest {
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
    }

    class MyAsyncJournal extends AsyncWriteJournal {
        @Override
        public Future<Long> doReplayAsync(String processorId, long fromSequenceNr, long toSequenceNr, Procedure<PersistentImpl> replayCallback) {
            return null;
        }

        @Override
        public Future<Void> doWriteAsync(PersistentImpl persistent) {
            return null;
        }

        @Override
        public Future<Void> doDeleteAsync(PersistentImpl persistent) {
            return null;
        }

        @Override
        public Future<Void> doConfirmAsync(String processorId, long sequenceNr, String channelId) {
            return null;
        }
    }
}
