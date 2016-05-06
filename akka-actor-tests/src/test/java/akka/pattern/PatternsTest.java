package akka.pattern;

import akka.actor.*;
import akka.dispatch.Futures;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.AkkaSpec;
import akka.testkit.TestProbe;
import org.junit.ClassRule;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

import static akka.pattern.Patterns.ask;
import static akka.pattern.Patterns.pipe;
import static org.junit.Assert.assertEquals;

/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
public class PatternsTest extends JUnitSuite {

    @ClassRule
    public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("JavaAPI",
            AkkaSpec.testConf());

    private final ActorSystem system = actorSystemResource.getSystem();


    @Test
    public void useAsk() throws Exception {
        ActorRef testActor = system.actorOf(Props.create(JavaAPITestActor.class), "test");
        assertEquals("Ask should return expected answer",
                JavaAPITestActor.ANSWER, Await.result(ask(testActor, "hey!", 3000), Duration.create(3, "seconds")));
    }

    @Test
    public void useAskWithActorSelection() throws Exception {
        ActorRef testActor = system.actorOf(Props.create(JavaAPITestActor.class), "test2");
        ActorSelection selection = system.actorSelection("/user/test2");
        ActorIdentity id = (ActorIdentity) Await.result(ask(selection, new Identify("yo!"), 3000), Duration.create(3, "seconds"));
        assertEquals("Ask (Identify) should return the proper ActorIdentity", testActor, id.getRef());
    }

    @Test
    public void usePipe() throws Exception {
        TestProbe probe = new TestProbe(system);
        pipe(Futures.successful("ho!"), system.dispatcher()).to(probe.ref());
        probe.expectMsg("ho!");
    }

    @Test
    public void usePipeWithActorSelection() throws Exception {
        TestProbe probe = new TestProbe(system);
        ActorSelection selection = system.actorSelection(probe.ref().path());
        pipe(Futures.successful("hi!"), system.dispatcher()).to(selection);
        probe.expectMsg("hi!");
    }
}
