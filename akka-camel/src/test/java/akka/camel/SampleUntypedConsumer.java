/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.camel;

import akka.camel.javaapi.UntypedConsumerActor;

/**
 *
 */
public class SampleUntypedConsumer extends UntypedConsumerActor {

    public String getEndpointUri() {
        return "direct:test-untyped-consumer";
    }

    public void onReceive(Object message) {
        CamelMessage msg = (CamelMessage)message;
        String body = msg.getBodyAs(String.class, getCamelContext());
        String header = msg.getHeaderAs("test", String.class,getCamelContext());
        sender().tell(String.format("%s %s", body, header), getSelf());
   }

}
