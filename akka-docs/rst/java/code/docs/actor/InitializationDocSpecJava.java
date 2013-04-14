package docs.actor;

import akka.actor.*;
import akka.japi.Procedure;
import akka.testkit.JavaTestKit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.Option;

/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
public class InitializationDocSpecJava {

    static public class PreStartInitExample extends UntypedActor {

        public void onReceive(Object message) throws Exception {}

        //#preStartInit
        @Override
        public void preStart() {
            // Initialize children here
        }

        // Overriding postRestart to disable the call to preStart()
        // after restarts
        @Override
        public void postRestart(Throwable reason) {
        }

        // The default implementation of preRestart() stops all the children
        // of the actor. To opt-out from stopping the children, we
        // have to override preRestart()
        @Override
        public void preRestart(Throwable reason, Option<Object> message) 
              throws Exception {
            // Keep the call to postStop(), but no stopping of children
            postStop();
        }
        //#preStartInit

    }

    public static class MessageInitExample extends UntypedActor {
        //#messageInit
        private String initializeMe = null;

        @Override
        public void onReceive(Object message) throws Exception {
            if (message.equals("init")) {
                initializeMe = "Up and running";
                getContext().become(new Procedure<Object>() {
                    @Override
                    public void apply(Object message) throws Exception {
                        if (message.equals("U OK?"))
                        getSender().tell(initializeMe, getSelf());
                    }
                });
            }
        }
        //#messageInit
    }

    static ActorSystem system;

    @BeforeClass
    public static void setup() {
        system = ActorSystem.create("TestSystem");
    }

    @AfterClass
    public static void teardown() {
        system.shutdown();
    }

    @Test
    public void testIt() {

        new JavaTestKit(system) {{
            ActorRef testactor = system.actorOf(Props.create(MessageInitExample.class), "testactor");
            String probe = "U OK?";

            testactor.tell(probe, getRef());
            expectNoMsg();

            testactor.tell("init", getRef());
            testactor.tell(probe, getRef());
            expectMsgEquals("Up and running");
        }};



    }

}
