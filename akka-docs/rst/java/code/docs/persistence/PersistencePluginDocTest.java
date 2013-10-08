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
    static Object o1 = new Object() {
        abstract class MySnapshotStore extends SnapshotStore {
            //#snapshot-store-plugin-api
            /**
             * Plugin Java API.
             *
             * Asynchronously loads a snapshot.
             *
             * @param processorId processor id.
             * @param criteria selection criteria for loading.
             */
            public abstract Future<Option<SelectedSnapshot>> doLoadAsync(String processorId, SnapshotSelectionCriteria criteria);

            /**
             * Plugin Java API.
             *
             * Asynchronously saves a snapshot.
             *
             * @param metadata snapshot metadata.
             * @param snapshot snapshot.
             */
            public abstract Future<Void> doSaveAsync(SnapshotMetadata metadata, Object snapshot);

            /**
             * Plugin Java API.
             *
             * Called after successful saving of a snapshot.
             *
             * @param metadata snapshot metadata.
             */
            public abstract void onSaved(SnapshotMetadata metadata) throws Exception;

            /**
             * Plugin Java API.
             *
             * Deletes the snapshot identified by `metadata`.
             *
             * @param metadata snapshot metadata.
             */
            public abstract void doDelete(SnapshotMetadata metadata) throws Exception;
            //#snapshot-store-plugin-api
        }

        abstract class MySyncWriteJournal extends SyncWriteJournal {
            //#sync-write-plugin-api
            /**
             * Plugin Java API.
             *
             * Synchronously writes a `persistent` message to the journal.
             */
            @Override
            public abstract void doWrite(PersistentImpl persistent) throws Exception;

            /**
             * Plugin Java API.
             *
             * Synchronously marks a `persistent` message as deleted.
             */
            @Override
            public abstract void doDelete(PersistentImpl persistent) throws Exception;

            /**
             * Plugin Java API.
             *
             * Synchronously writes a delivery confirmation to the journal.
             */
            @Override
            public abstract void doConfirm(String processorId, long sequenceNr, String channelId) throws Exception;
            //#sync-write-plugin-api
        }

        abstract class MyAsyncWriteJournal extends AsyncWriteJournal {
            //#async-write-plugin-api
            /**
             * Plugin Java API.
             *
             * Asynchronously writes a `persistent` message to the journal.
             */
            @Override
            public abstract Future<Void> doWriteAsync(PersistentImpl persistent);

            /**
             * Plugin Java API.
             *
             * Asynchronously marks a `persistent` message as deleted.
             */
            @Override
            public abstract Future<Void> doDeleteAsync(PersistentImpl persistent);

            /**
             * Plugin Java API.
             *
             * Asynchronously writes a delivery confirmation to the journal.
             */
            @Override
            public abstract Future<Void> doConfirmAsync(String processorId, long sequenceNr, String channelId);
            //#async-write-plugin-api
        }

        abstract class MyAsyncReplay extends AsyncReplay {
            //#async-replay-plugin-api
            /**
             * Plugin Java API.
             *
             * Asynchronously replays persistent messages. Implementations replay a message
             * by calling `replayCallback`. The returned future must be completed when all
             * messages (matching the sequence number bounds) have been replayed. The future
             * `Long` value must be the highest stored sequence number in the journal for the
             * specified processor. The future must be completed with a failure if any of
             * the persistent messages could not be replayed.
             *
             * The `replayCallback` must also be called with messages that have been marked
             * as deleted. In this case a replayed message's `deleted` field must be set to
             * `true`.
             *
             * The channel ids of delivery confirmations that are available for a replayed
             * message must be contained in that message's `confirms` sequence.
             *
             * @param processorId processor id.
             * @param fromSequenceNr sequence number where replay should start.
             * @param toSequenceNr sequence number where replay should end (inclusive).
             * @param replayCallback called to replay a single message.
             */
            @Override
            public abstract Future<Long> doReplayAsync(String processorId, long fromSequenceNr, long toSequenceNr, Procedure<PersistentImpl> replayCallback);
            //#async-replay-plugin-api
        }
    };
}
