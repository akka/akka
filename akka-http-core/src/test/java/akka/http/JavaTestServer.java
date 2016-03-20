/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.JavaApiTestCases;
import akka.http.javadsl.model.ws.Message;
import akka.http.javadsl.model.ws.TextMessage;
import akka.http.javadsl.model.ws.WebSocket;
import akka.japi.JavaPartialFunction;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Source;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class JavaTestServer {
    public static void main(String[] args) throws Exception {
        ActorSystem system = ActorSystem.create();

        try {
            final Materializer materializer = ActorMaterializer.create(system);

            CompletionStage<ServerBinding> serverBindingFuture =
                    Http.get(system).bindAndHandleSync(
                      request -> {
                          System.out.println("Handling request to " + request.getUri());

                          if (request.getUri().path().equals("/"))
                              return WebSocket.handleWebSocketRequestWith(request, echoMessages());
                          else if (request.getUri().path().equals("/greeter"))
                              return WebSocket.handleWebSocketRequestWith(request, greeter());
                          else
                              return JavaApiTestCases.handleRequest(request);
                      }, ConnectHttp.toHost("localhost", 8080), materializer);

            serverBindingFuture.toCompletableFuture().get(1, TimeUnit.SECONDS); // will throw if binding fails
            System.out.println("Press ENTER to stop.");
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        } finally {
            system.terminate();
        }
    }

    public static Flow<Message, Message, NotUsed> echoMessages() {
        return Flow.create(); // the identity operation
    }

    public static Flow<Message, Message, NotUsed> greeter() {
        return
            Flow.<Message>create()
                .collect(new JavaPartialFunction<Message, Message>() {
                    @Override
                    public Message apply(Message msg, boolean isCheck) throws Exception {
                        if (isCheck)
                            if (msg.isText()) return null;
                            else throw noMatch();
                        else
                            return handleTextMessage(msg.asTextMessage());
                    }
                });
    }
    public static TextMessage handleTextMessage(TextMessage msg) {
        if (msg.isStrict())
            return TextMessage.create("Hello "+msg.getStrictText());
        else
            return TextMessage.create(Source.single("Hello ").concat(msg.getStreamedText()));
    }
}
