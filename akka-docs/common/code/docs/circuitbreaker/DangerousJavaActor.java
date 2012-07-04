/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.circuitbreaker;

//#imports1

import akka.actor.UntypedActor;
import scala.concurrent.Future;
import akka.event.LoggingAdapter;
import scala.concurrent.util.Duration;
import akka.pattern.CircuitBreaker;
import akka.event.Logging;

import static scala.concurrent.Futures.future;

import java.util.concurrent.Callable;

//#imports1

//#circuit-breaker-initialization
public class DangerousJavaActor extends UntypedActor {

    private final CircuitBreaker breaker;
    private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    public DangerousJavaActor() {
        this.breaker = new CircuitBreaker(
                getContext().dispatcher(), getContext().system().scheduler(),
                5, Duration.parse("10s"), Duration.parse("1m"))
                .onOpen(new Callable<Object>() {
                    public Object call() throws Exception {
                        notifyMeOnOpen();
                        return null;
                    }
                });
    }

    public void notifyMeOnOpen() {
        log.warning("My CircuitBreaker is now open, and will not close for one minute");
    }
//#circuit-breaker-initialization

    //#circuit-breaker-usage
    public String dangerousCall() {
        return "This really isn't that dangerous of a call after all";
    }

    @Override
    public void onReceive(Object message) {
        if (message instanceof String) {
            String m = (String) message;
            if ("is my middle name".equals(m)) {
                final Future<String> f = future(
                        new Callable<String>() {
                            public String call() {
                                return dangerousCall();
                            }
                        }, getContext().dispatcher());

                getSender().tell(breaker
                        .callWithCircuitBreaker(
                                new Callable<Future<String>>() {
                                    public Future<String> call() throws Exception {
                                        return f;
                                    }
                                }));
            }
            if ("block for me".equals(m)) {
                getSender().tell(breaker
                        .callWithSyncCircuitBreaker(
                                new Callable<String>() {
                                    @Override
                                    public String call() throws Exception {
                                        return dangerousCall();
                                    }
                                }));
            }
        }
    }
//#circuit-breaker-usage

}