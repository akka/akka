/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.camel;

import akka.camel.javaapi.UntypedProducerActor;
/** */
public class SampleUntypedForwardingProducer extends UntypedProducerActor {

  public String getEndpointUri() {
    return "direct:producer-test-1";
  }

  @Override
  public void onRouteResponse(Object message) {
    CamelMessage msg = (CamelMessage) message;
    String body = msg.getBodyAs(String.class, getCamelContext());
    getProducerTemplate().sendBody("direct:forward-test-1", body);
  }
}
