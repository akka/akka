package akka.tutorial.first.java;

import akka.actor.ActorSystem;
import akka.testkit.TestActor;
import akka.testkit.TestActorRef;
import akka.testkit.TestProbe;
import akka.util.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import scala.runtime.AbstractFunction0;

import static org.junit.Assert.assertEquals;

/**
 * Tests the Worker actor using the akka-testkit.
 *
 * @author Florian Hopf
 */
public class WorkerTest {

    private TestActorRef<Pi.Worker> actorRef;
    private ActorSystem actorSystem;

    @Before
    public void initActor() {
        actorSystem = ActorSystem.apply();
        actorRef = TestActorRef.apply(new AbstractFunction0<Pi.Worker>() {

            @Override
            public Pi.Worker apply() {
                return new Pi.Worker();
            }
        }, actorSystem);
    }

    @Test
    public void doNothingForString() {
        TestProbe testProbe = TestProbe.apply(actorSystem);
        actorRef.tell("Hello", testProbe.ref());

        testProbe.expectNoMsg(Duration.apply(100, TimeUnit.MILLISECONDS));
    }

    @Test
    public void calculatePiFor0() {
        TestProbe testProbe = TestProbe.apply(actorSystem);
        Pi.Work work = new Pi.Work(0, 0);
        actorRef.tell(work, testProbe.ref());

        testProbe.expectMsgClass(Pi.Result.class);
        TestActor.Message message = testProbe.lastMessage();
        Pi.Result resultMsg = (Pi.Result) message.msg();
        assertEquals(0.0, resultMsg.getValue(), 0.0000000001);
    }

    @Test
    public void calculatePiFor1() {
        TestProbe testProbe = TestProbe.apply(actorSystem);
        Pi.Work work = new Pi.Work(1, 1);
        actorRef.tell(work, testProbe.ref());

        testProbe.expectMsgClass(Pi.Result.class);
        TestActor.Message message = testProbe.lastMessage();
        Pi.Result resultMsg = (Pi.Result) message.msg();
        assertEquals(-1.3333333333333333, resultMsg.getValue(), 0.0000000001);
    }

    @After
    public void shutdownActorSystem() {
        actorSystem.shutdown();
    }
}
