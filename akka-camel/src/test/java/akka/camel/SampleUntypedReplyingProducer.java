/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.camel;

import akka.camel.javaapi.UntypedProducerActor;

/**
 *
 */
public class SampleUntypedReplyingProducer extends UntypedProducerActor {

    public String getEndpointUri() {
        return "direct:producer-test-1";
    }

}
