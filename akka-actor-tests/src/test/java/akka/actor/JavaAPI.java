package akka.actor;

import akka.actor.ActorSystem;
import akka.japi.Creator;
import akka.testkit.AkkaSpec;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class JavaAPI {

  private static ActorSystem system;

  @BeforeClass
  public static void beforeAll() {
    system = ActorSystem.create("JavaAPI", AkkaSpec.testConf());
  }

  @AfterClass
  public static void afterAll() {
    system.stop();
    system = null;
  }

  @Test
  public void mustBeAbleToCreateActorRefFromClass() {
    ActorRef ref = system.actorOf(JavaAPITestActor.class);
    assertNotNull(ref);
  }

  @Test
  public void mustBeAbleToCreateActorRefFromFactory() {
    ActorRef ref = system.actorOf(new Props().withCreator(new Creator<Actor>() {
      public Actor create() {
        return new JavaAPITestActor();
      }
    }));
    assertNotNull(ref);
  }

  @Test
  public void mustAcceptSingleArgTell() {
    ActorRef ref = system.actorOf(JavaAPITestActor.class);
    ref.tell("hallo");
    ref.tell("hallo", ref);
  }
}
