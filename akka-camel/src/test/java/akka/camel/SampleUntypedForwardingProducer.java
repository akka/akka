/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel;

import akka.camel.javaapi.UntypedProducerActor;
/**
 * @author Martin Krasser
 */
public class SampleUntypedForwardingProducer extends UntypedProducerActor {

    public String getEndpointUri() {
        return "direct:producer-test-1";
    }

    @Override
    public void onReceiveAfterProduce(Object message) {
        RichMessage msg = rich((Message)message);
        String body = msg.getBodyAs(String.class);
        getProducerTemplate().sendBody("direct:forward-test-1", body);
    }
}
