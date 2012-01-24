/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.util.FiniteDuration;
import org.junit.AfterClass;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;


/**
 * @author Martin Krasser
 */
public class ConsumerJavaTestBase {

    static ActorSystem system = ActorSystem.create("test");
    static Camel camel = ((Camel) CamelExtension.get(system));


    @AfterClass
    public static void tearDownAfterClass() {
        system.shutdown();
    }

    @Test
    public void shouldHandleExceptionThrownByActorAndGenerateCustomResponse() {
        ActorRef ref = system.actorOf(new Props().withCreator(SampleErrorHandlingConsumer.class));
        camel.awaitActivation(ref, new FiniteDuration(1, TimeUnit.SECONDS));

        String result = camel.template().requestBody("direct:error-handler-test-java", "hello", String.class);
        assertEquals("error: hello", result);
    }
}
