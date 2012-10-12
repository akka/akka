/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel;

import akka.actor.Status;
import akka.camel.javaapi.UntypedConsumerActor;
import akka.dispatch.Mapper;
import scala.concurrent.duration.Duration;
import org.apache.camel.builder.Builder;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import scala.Option;
import scala.concurrent.duration.FiniteDuration;

/**
 * @author Martin Krasser
 */
public class SampleErrorHandlingConsumer extends UntypedConsumerActor {
    private static Mapper<RouteDefinition, ProcessorDefinition<?>> mapper = new Mapper<RouteDefinition, ProcessorDefinition<?>>() {
        public ProcessorDefinition<?> apply(RouteDefinition rd) {
            return rd.onException(Exception.class).handled(true).transform(Builder.exceptionMessage()).end();
        }
    };

    public String getEndpointUri() {
        return "direct:error-handler-test-java";
    }

    @Override
    public Mapper<RouteDefinition, ProcessorDefinition<?>> onRouteDefinition() {
      return mapper;
    }

    @Override
    public FiniteDuration replyTimeout(){
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
