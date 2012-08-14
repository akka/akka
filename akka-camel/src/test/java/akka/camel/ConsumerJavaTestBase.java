/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import scala.concurrent.Await;
import scala.concurrent.ExecutionContext;
import scala.concurrent.util.Duration;
import org.junit.AfterClass;
import org.junit.Test;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;


/**
 * @author Martin Krasser
 */
public class ConsumerJavaTestBase {

    static ActorSystem system = ActorSystem.create("test");
    static Camel camel = CamelExtension.get(system);


    @AfterClass
    public static void tearDownAfterClass() {
        system.shutdown();
    }

    @Test
    public void shouldHandleExceptionThrownByActorAndGenerateCustomResponse() throws Exception {
        Duration timeout = Duration.create(1, TimeUnit.SECONDS);
        ExecutionContext executionContext = system.dispatcher();
        ActorRef ref = Await.result(
          camel.activationFutureFor(system.actorOf(new Props(SampleErrorHandlingConsumer.class)), timeout, executionContext),
          timeout);

        String result = camel.template().requestBody("direct:error-handler-test-java", "hello", String.class);
        assertEquals("error: hello", result);
    }
}
