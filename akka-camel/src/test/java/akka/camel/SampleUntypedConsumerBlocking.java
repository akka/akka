/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel;

import akka.camel.javaapi.UntypedConsumerActor;
import akka.util.Duration;


/**
 * @author Martin Krasser
 */
public class SampleUntypedConsumerBlocking extends UntypedConsumerActor {

    public String getEndpointUri() {
        return "direct:test-untyped-consumer-blocking";
    }

    public Duration replyTimeout(){
        return Duration.create(1, "second");
    }

    public void onReceive(Object message) {
        Message msg = (Message)message;
        String body = msg.getBodyAs(String.class);
        String header = msg.getHeaderAs("test", String.class);
        sender().tell(String.format("%s %s", body, header));
   }

}
