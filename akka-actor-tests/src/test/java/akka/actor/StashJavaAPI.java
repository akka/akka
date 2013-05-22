package akka.actor;

import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.TestProbe;

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
    ActorRef ref = system.actorOf(Props.create(StashJavaAPITestActor.class));
    final TestProbe probe = new TestProbe(system);
    probe.send(ref, "Hello");
    probe.send(ref, "Hello2");
    probe.send(ref, "Hello12");
    probe.expectMsg(5);
  }

}
