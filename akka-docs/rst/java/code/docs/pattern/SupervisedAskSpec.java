package docs.pattern;

import scala.concurrent.Await;
import scala.concurrent.Future;
import akka.actor.ActorRef;
import akka.actor.ActorRefFactory;
import akka.actor.Props;
import akka.actor.AbstractActor;
import akka.util.Timeout;

public class SupervisedAskSpec {

  public Object execute(Class<? extends AbstractActor> someActor,
      Object message, Timeout timeout, ActorRefFactory actorSystem)
      throws Exception {
    // example usage
    try {
      ActorRef supervisorCreator = SupervisedAsk
          .createSupervisorCreator(actorSystem);
      Future<Object> finished = SupervisedAsk.askOf(supervisorCreator,
          Props.create(someActor), message, timeout);
      return Await.result(finished, timeout.duration());
    } catch (Exception e) {
      // exception propagated by supervision
      throw e;
    }
  }
}
