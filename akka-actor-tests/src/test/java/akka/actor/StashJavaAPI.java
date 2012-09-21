package akka.actor;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.typesafe.config.ConfigFactory;

public class StashJavaAPI {

  private static ActorSystem system;

  @BeforeClass
  public static void beforeAll() {
    system = ActorSystem.create("StashJavaAPI",
        ConfigFactory.parseString(ActorWithStashSpec.testConf()));
  }

  @AfterClass
  public static void afterAll() {
    system.shutdown();
    system = null;
  }

  @Test
  public void mustBeAbleToUseStash() {
    ActorRef ref = system.actorOf(new Props(StashJavaAPITestActor.class)
        .withDispatcher("my-dispatcher"));
    ref.tell("Hello", ref);
    ref.tell("Hello", ref);
    ref.tell(new Object(), null);
  }

}
