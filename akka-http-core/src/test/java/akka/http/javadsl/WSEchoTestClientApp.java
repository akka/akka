/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl;

import akka.actor.ActorSystem;
import akka.dispatch.Futures;
import akka.http.javadsl.model.ws.Message;
import akka.http.javadsl.model.ws.TextMessage;
import akka.http.javadsl.model.ws.WebsocketRequest;
import akka.japi.function.Function;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

import java.util.Arrays;
import java.util.List;

public class WSEchoTestClientApp {
    private static final Function<Message, String> messageStringifier = new Function<Message, String>() {
        @Override
        public String apply(Message msg) throws Exception {
            if (msg.isText() && msg.asTextMessage().isStrict())
                return msg.asTextMessage().getStrictText();
            else
                throw new IllegalArgumentException("Unexpected message "+msg);
        }
    };

    public static void main(String[] args) throws Exception {
        ActorSystem system = ActorSystem.create();

        try {
            final Materializer materializer = ActorMaterializer.create(system);

            final Future<Message> ignoredMessage = Futures.successful((Message) TextMessage.create("blub"));
            final Future<Message> delayedCompletion =
                akka.pattern.Patterns.after(
                        FiniteDuration.apply(1, "second"),
                        system.scheduler(),
                        system.dispatcher(),
                        ignoredMessage);

            Source<Message, BoxedUnit> echoSource =
                Source.from(Arrays.<Message>asList(
                        TextMessage.create("abc"),
                        TextMessage.create("def"),
                        TextMessage.create("ghi")
                )).concat(Source.from(delayedCompletion).drop(1));

            Sink<Message, Future<List<String>>> echoSink =
                Flow.of(Message.class)
                    .map(messageStringifier)
                    .grouped(1000)
                    .toMat(Sink.<List<String>>head(), Keep.<BoxedUnit, Future<List<String>>>right());

            Flow<Message, Message, Future<List<String>>> echoClient =
                Flow.fromSinkAndSourceMat(echoSink, echoSource, Keep.<Future<List<String>>, BoxedUnit>left());

            Future<List<String>> result =
                Http.get(system).singleWebsocketRequest(
                    WebsocketRequest.create("ws://echo.websocket.org"),
                    echoClient,
                    materializer
                ).second();

            List<String> messages = Await.result(result, FiniteDuration.apply(10, "second"));
            System.out.println("Collected " + messages.size() + " messages:");
            for (String msg: messages)
                System.out.println(msg);
        } finally {
            system.shutdown();
        }
    }
}
