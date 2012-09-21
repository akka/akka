/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel;

import akka.actor.Status;
import akka.camel.javaapi.UntypedConsumerActor;
import scala.concurrent.util.Duration;
import org.apache.camel.builder.Builder;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import scala.Option;

/**
 * @author Martin Krasser
 */
public class SampleErrorHandlingConsumer extends UntypedConsumerActor {

    public String getEndpointUri() {
        return "direct:error-handler-test-java";
    }

    @Override
    //TODO write test confirming this gets called in java
    public ProcessorDefinition onRouteDefinition(RouteDefinition rd) {
        return rd.onException(Exception.class).handled(true).transform(Builder.exceptionMessage()).end();
    }

    @Override
    public Duration replyTimeout(){
        return Duration.create(1, "second");
    }



    public void onReceive(Object message) throws Exception {
        CamelMessage msg = (CamelMessage) message;
        String body = msg.getBodyAs(String.class,this.getCamelContext());
        throw new Exception(String.format("error: %s", body));
    }

    @Override
    public void preRestart(Throwable reason, Option<Object> message){
        getSender().tell(new Status.Failure(reason), getSelf());
    }

}
