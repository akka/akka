package akka.actor;

import akka.testkit.AkkaJUnitActorSystemResource;
import org.junit.ClassRule;
import org.junit.Test;

import com.typesafe.config.ConfigFactory;

public class StashJavaAPI {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("StashJavaAPI", ActorWithBoundedStashSpec.testConf());

  private final ActorSystem system = actorSystemResource.getSystem();

  @Test
  public void mustBeAbleToUseStash() {
    ActorRef ref = system.actorOf(Props.create(StashJavaAPITestActor.class)
        .withDispatcher("my-dispatcher"));
    ref.tell("Hello", ref);
    ref.tell("Hello", ref);
    ref.tell(new Object(), null);
  }

}
