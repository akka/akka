package docs.pattern;

import akka.actor.ActorRef;
import akka.actor.ActorRefFactory;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.dispatch.Await;
import akka.dispatch.Future;
import akka.util.Timeout;

public class SupervisedAskSpec {

  public Object execute(Class<? extends UntypedActor> someActor,
      Object message, Timeout timeout, ActorRefFactory actorSystem)
      throws Exception {
    // example usage
    try {
      ActorRef supervisorCreator = SupervisedAsk
          .createSupervisorCreator(actorSystem);
      Future<Object> finished = SupervisedAsk.askOf(supervisorCreator,
          Props.apply(someActor), message, timeout);
      return Await.result(finished, timeout.duration());
    } catch (Exception e) {
      // exception propagated by supervision
      throw e;
    }
  }
}
