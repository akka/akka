package jdocs.persistence.testkit;

import akka.actor.typed.ActorSystem;
import akka.persistence.testkit.DeleteEvents;
import akka.persistence.testkit.DeleteSnapshotByMeta;
import akka.persistence.testkit.DeleteSnapshotsByCriteria;
import akka.persistence.testkit.JournalOperation;
import akka.persistence.testkit.PersistenceTestKitPlugin;
import akka.persistence.testkit.ProcessingPolicy;
import akka.persistence.testkit.ProcessingResult;
import akka.persistence.testkit.ProcessingSuccess;
import akka.persistence.testkit.ReadEvents;
import akka.persistence.testkit.ReadSeqNum;
import akka.persistence.testkit.ReadSnapshot;
import akka.persistence.testkit.Reject;
import akka.persistence.testkit.SnapshotOperation;
import akka.persistence.testkit.StorageFailure;
import akka.persistence.testkit.WriteEvents;
import akka.persistence.testkit.WriteSnapshot;
import akka.persistence.testkit.javadsl.PersistenceTestKit;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.EventHandler;
import akka.persistence.typed.javadsl.EventSourcedBehavior;
import com.typesafe.config.ConfigFactory;
import jdocs.AbstractJavaTest;
import org.junit.Test;

public class TestKitExamples {

    //#testkit-typed-usecase
    class SampleTest extends AbstractJavaTest {

        ActorSystem<Cmd> system =
                ActorSystem.create(new YourPersistentBehavior(PersistenceId.ofUniqueId("some-id")),
                        "example",
                        PersistenceTestKitPlugin.getInstance()
                                .config()
                                .withFallback(ConfigFactory.defaultApplication()));

        @Test
        void test() {
            PersistenceTestKit testKit = PersistenceTestKit.create(system);

            Cmd cmd = new Cmd("data");
            system.tell(cmd);
            Evt expectedEventPersisted = new Evt(cmd.getData());

            testKit.expectNextPersisted("your-persistence-id", expectedEventPersisted);
            testKit.clearAll();
        }
    }

    class YourPersistentBehavior extends EventSourcedBehavior<Cmd, Evt, EmptyState> {

        public YourPersistentBehavior(PersistenceId persistenceId) {
            super(persistenceId);
        }

        @Override
        public EmptyState emptyState() {
            // some state
            return new EmptyState();
        }

        @Override
        public CommandHandler<Cmd, Evt, EmptyState> commandHandler() {
            return newCommandHandlerBuilder()
                    .forAnyState()
                    .onCommand(Cmd.class, command -> Effect().persist(new Evt(command.getData())))
                    .build();
        }

        @Override
        public EventHandler<EmptyState, Evt> eventHandler() {
            // some logic
            return null;
        }
    }
    //#testkit-typed-usecase

    // #set-event-storage-policy
    class SampleEventStoragePolicy implements ProcessingPolicy<JournalOperation> {

        // you can use internal state, it need not to be thread safe
        int count = 1;

        @Override
        public ProcessingResult tryProcess(String persistenceId, JournalOperation processingUnit) {
            // check the type of operation and react with success or with reject or with failure.
            // if you return ProcessingSuccess the operation will be performed, otherwise not.
            if (count < 10) {
                count += 1;
                if (processingUnit instanceof ReadEvents) {
                    ReadEvents read = (ReadEvents) processingUnit;
                    if (read.batch().nonEmpty()) {
                        ProcessingSuccess.getInstance();
                    } else {
                        return StorageFailure.create();
                    }
                } else if (processingUnit instanceof WriteEvents) {
                    return ProcessingSuccess.getInstance();
                } else if (processingUnit instanceof DeleteEvents) {
                    return ProcessingSuccess.getInstance();
                } else if (processingUnit.equals(ReadSeqNum.getInstance())) {
                    return Reject.create();
                }
                // you can set your own exception
                return StorageFailure.create(new RuntimeException("your exception"));
            } else {
                return ProcessingSuccess.getInstance();
            }
        }
    }
    // #set-event-storage-policy

    // #set-snapshot-storage-policy
    class SnapshotStoragePolicy implements ProcessingPolicy<SnapshotOperation> {

        // you can use internal state, it doesn't need to be thread safe
        int count = 1;

        @Override
        public ProcessingResult tryProcess(String persistenceId, SnapshotOperation processingUnit) {
            // check the type of operation and react with success or with failure.
            // if you return ProcessingSuccess the operation will be performed, otherwise not.
            if (count < 10) {
                count += 1;
                if (processingUnit instanceof ReadSnapshot) {
                    ReadSnapshot read = (ReadSnapshot) processingUnit;
                    if (read.getSnapshot().isPresent()) {
                        ProcessingSuccess.getInstance();
                    } else {
                        return StorageFailure.create();
                    }
                } else if (processingUnit instanceof WriteSnapshot) {
                    return ProcessingSuccess.getInstance();
                } else if (processingUnit instanceof DeleteSnapshotsByCriteria) {
                    return ProcessingSuccess.getInstance();
                } else if (processingUnit instanceof DeleteSnapshotByMeta) {
                    return ProcessingSuccess.getInstance();
                }
                // you can set your own exception
                return StorageFailure.create(new RuntimeException("your exception"));
            } else {
                return ProcessingSuccess.getInstance();
            }
        }
    }
    // #set-snapshot-storage-policy

}

class Cmd {

    private String data;

    Cmd(String data) {
        this.data = data;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }
}

class Evt {

    private String data;

    Evt(String data) {
        this.data = data;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }
}

class EmptyState{}