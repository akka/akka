/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io;

import akka.actor.ActorRef;
import akka.japi.Pair;
import akka.japi.function.Procedure;
import akka.stream.StreamTest;
import akka.stream.javadsl.AkkaJUnitActorSystemResource;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.testkit.AkkaSpec;
import akka.stream.testkit.Utils;
import akka.testkit.JavaTestKit;
import akka.util.ByteString;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;
import org.junit.Test;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class OutputStreamSourceTest extends StreamTest {
    public OutputStreamSourceTest() {
        super(actorSystemResource);
    }

    @ClassRule
    public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("OutputStreamSource",
            Utils.UnboundedMailboxConfig());
    @Test
    public void mustSendEventsViaOutputStream() throws Exception {
        final FiniteDuration timeout = FiniteDuration.create(300, TimeUnit.MILLISECONDS);
        final JavaTestKit probe = new JavaTestKit(system);

        final Source<ByteString, OutputStream> source = Source.outputStream(timeout);
        final OutputStream s = source.to(Sink.foreach(new Procedure<ByteString>() {
            public void apply(ByteString elem) {
                probe.getRef().tell(elem, ActorRef.noSender());
            }
        })).run(materializer);

        s.write("a".getBytes());
        assertEquals(ByteString.fromString("a"), probe.receiveOne(timeout));
        s.close();

    }

}
