package sample.java8;

//#imports
import akka.actor.AbstractActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.pf.ReceiveBuilder;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

//#imports

//#my-actor
public class MyActor extends AbstractActor {
  private final LoggingAdapter log = Logging.getLogger(context().system(), this);

  @Override
  public PartialFunction<Object, BoxedUnit> receive() {
    return ReceiveBuilder.
      match(String.class, s -> s.equals("test"), s -> log.info("received test")).
      matchAny(o -> log.info("received unknown message")).build();
  }
}
//#my-actor
