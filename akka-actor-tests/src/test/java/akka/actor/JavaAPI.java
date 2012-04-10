package akka.actor;

import akka.actor.ActorSystem;
import akka.event.Logging;
import akka.event.Logging.LoggerInitialized;
import akka.japi.Creator;
import akka.routing.CurrentRoutees;
import akka.routing.FromConfig;
import akka.routing.NoRouter;
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
    system.shutdown();
    system = null;
  }
  
  // compilation tests
  @SuppressWarnings("unused")
  public void mustCompile() {
    final Kill kill = Kill.instance();
    final PoisonPill pill = PoisonPill.instance();
    final ReceiveTimeout t = ReceiveTimeout.instance();

    final LocalScope ls = LocalScope.instance();
    final NoScopeGiven noscope = NoScopeGiven.instance();
    
    final LoggerInitialized x = Logging.loggerInitialized();
    
    final CurrentRoutees r = CurrentRoutees.instance();
    final NoRouter nr = NoRouter.instance();
    final FromConfig fc = FromConfig.instance();
  }

  @Test
  public void mustBeAbleToCreateActorRefFromClass() {
    ActorRef ref = system.actorOf(new Props(JavaAPITestActor.class));
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
    ActorRef ref = system.actorOf(new Props(JavaAPITestActor.class));
    ref.tell("hallo");
    ref.tell("hallo", ref);
  }
}
