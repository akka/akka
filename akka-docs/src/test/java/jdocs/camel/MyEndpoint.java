/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.camel;

//#Consumer-mina
import akka.camel.CamelMessage;
import akka.camel.javaapi.UntypedConsumerActor;

public class MyEndpoint extends UntypedConsumerActor{
  private String uri;

  public String getEndpointUri() {
    return uri;
  }

  public void onReceive(Object message) throws Exception {
    if (message instanceof CamelMessage) {
      /* ... */
    } else
      unhandled(message);
  }

  // Extra constructor to change the default uri,
  // for instance to "jetty:http://localhost:8877/example"
  public MyEndpoint(String uri) {
    this.uri = uri;
  }

  public MyEndpoint() {
    this.uri = "mina2:tcp://localhost:6200?textline=true";
  }
}
//#Consumer-mina