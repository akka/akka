/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel;

import akka.camel.javaapi.UntypedConsumerActor;

/**
 * @author Martin Krasser
 */
public class SampleUntypedConsumer extends UntypedConsumerActor {

    public String getEndpointUri() {
        return "direct:test-untyped-consumer";
    }

    public void onReceive(Object message) {
        CamelMessage msg = (CamelMessage)message;
        String body = msg.getBodyAs(String.class, getCamelContext());
        String header = msg.getHeaderAs("test", String.class,getCamelContext());
        sender().tell(String.format("%s %s", body, header));
   }

}
