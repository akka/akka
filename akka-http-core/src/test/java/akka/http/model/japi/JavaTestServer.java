/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import static akka.pattern.Patterns.ask;

public abstract class JavaTestServer {

// FIXME Java Http API
//  public static void main(String[] args) throws IOException, InterruptedException {
//    ActorSystem system = ActorSystem.create();
//
//    final FlowMaterializer materializer = FlowMaterializer.create(system);
//
//    ActorRef httpManager = Http.get(system).manager();
//    Future<Object> binding = ask(httpManager, Http.bind("localhost", 8080), 1000);
//    binding.foreach(new Foreach<Object>() {
//      @Override
//      public void each(Object result) throws Throwable {
//        ServerBinding binding = (ServerBinding) result;
//        System.out.println("Bound to " + binding.localAddress());
//
//        Source.from(binding.getConnectionStream()).foreach(new akka.stream.javadsl.japi.Procedure<IncomingConnection>() {
//          @Override
//          public void apply(IncomingConnection conn) throws Exception {
//            System.out.println("New incoming connection from " + conn.remoteAddress());
//
//            Source.from(conn.getRequestPublisher()).map(new akka.stream.javadsl.japi.Function<HttpRequest, HttpResponse>() {
//              @Override
//              public HttpResponse apply(HttpRequest request) throws Exception {
//                System.out.println("Handling request to " + request.getUri());
//                return JavaApiTestCases.handleRequest(request);
//              }
//            }).runWith(Sink.create(conn.getResponseSubscriber()), materializer);
//          }
//        }, materializer);
//      }
//    }, system.dispatcher());
//
//    System.out.println("Press ENTER to stop.");
//    new BufferedReader(new InputStreamReader(System.in)).readLine();
//
//    system.shutdown();
//  }
}
