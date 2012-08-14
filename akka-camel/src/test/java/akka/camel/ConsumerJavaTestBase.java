/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.JavaTestKit;
import scala.concurrent.Await;
import scala.concurrent.util.Duration;
import org.junit.AfterClass;
import org.junit.Test;
import java.util.concurrent.TimeUnit;
import akka.testkit.AkkaSpec;
import akka.testkit.JavaTestKit.EventFilter;

import static org.junit.Assert.assertEquals;


/**
 * @author Martin Krasser
 */
public class ConsumerJavaTestBase {

    static ActorSystem system = ActorSystem.create("test", AkkaSpec.testConf());
    static Camel camel = CamelExtension.get(system);


    @AfterClass
    public static void tearDownAfterClass() {
        system.shutdown();
    }

    @Test
    public void shouldHandleExceptionThrownByActorAndGenerateCustomResponse() throws Exception {
        new JavaTestKit(system) {{
            String result = new EventFilter<String>(Exception.class) {
                protected String run() {
                    Duration timeout = Duration.create(1, TimeUnit.SECONDS);
                    try {
                        ActorRef ref = Await.result(
                                camel.activationFutureFor(system.actorOf(new Props(SampleErrorHandlingConsumer.class)), timeout),
                                timeout);
                        return camel.template().requestBody("direct:error-handler-test-java", "hello", String.class);
                    }
                    catch (Exception e) {
                        return e.getMessage();
                    }
                }
            }.occurrences(1).exec();
            assertEquals("error: hello", result);
        }};
    }
}
