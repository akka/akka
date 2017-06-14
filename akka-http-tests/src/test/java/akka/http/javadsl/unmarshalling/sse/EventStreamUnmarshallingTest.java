/*
 * Copyright 2015 Heiko Seeberger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.javadsl.unmarshalling.sse;

import akka.actor.ActorSystem;
import akka.http.javadsl.model.HttpEntity;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Sink;
import akka.http.javadsl.model.sse.ServerSentEvent;
import akka.http.scaladsl.unmarshalling.sse.EventStreamUnmarshallingSpec;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import static scala.compat.java8.FutureConverters.toJava;

public class EventStreamUnmarshallingTest extends JUnitSuite {

    @Test
    public void testFromEventStream() throws Exception {
        ActorSystem system = ActorSystem.create();
        try {
            Materializer mat = ActorMaterializer.create(system);

            List<ServerSentEvent> events = EventStreamUnmarshallingSpec.eventsAsJava();
            HttpEntity entity = EventStreamUnmarshallingSpec.entity();

            List<ServerSentEvent> unmarshalledEvents =
                    EventStreamUnmarshalling.fromEventStream()
                            .unmarshal(entity, system.dispatcher(), mat)
                            .thenCompose(source -> source.runWith(Sink.seq(), mat))
                            .toCompletableFuture()
                            .get(3000, TimeUnit.SECONDS);

            Assert.assertEquals(events, unmarshalledEvents);
        } finally {
            toJava(system.terminate()).toCompletableFuture().get(42, TimeUnit.SECONDS);
        }
    }
}
