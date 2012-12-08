package docs.pattern;

import docs.testkit.TestKitSampleTest.SomeActor;
import scala.actors.Future;
import actor.ActorRef;
import actor.Props;

public class SupervisedAskSpec {

  public void execute() {
    // example usage
    try {
      ActorRef supervisorCreator = SupervisedAsk
          .createSupervisorCreator(actorSystem);
      Future<Object> finished = SupervisedAsk.askOf(supervisorCreator,
          Props.apply(SomeActor.class), message, timeout);
      Object result = Await.result(finished,
          timeout.duration());
    } catch (Exception e) {
      // exception propagated by supervision
    }
  }
}
