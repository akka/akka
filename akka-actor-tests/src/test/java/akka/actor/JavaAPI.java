/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor;

import akka.event.Logging;
import akka.event.Logging.LoggerInitialized;
import akka.japi.Creator;
import akka.routing.CurrentRoutees;
import akka.routing.FromConfig;
import akka.routing.NoRouter;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.AkkaSpec;

import org.junit.ClassRule;
import org.junit.Test;
import static org.junit.Assert.*;

public class JavaAPI {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("JAvaAPI", AkkaSpec.testConf());

  private final ActorSystem system = actorSystemResource.getSystem();

  // compilation tests
  @SuppressWarnings("unused")
  public void mustCompile() {
    final Kill kill = Kill.getInstance();
    final PoisonPill pill = PoisonPill.getInstance();
    final ReceiveTimeout t = ReceiveTimeout.getInstance();

    final LocalScope ls = LocalScope.getInstance();
    final NoScopeGiven noscope = NoScopeGiven.getInstance();
    
    final LoggerInitialized x = Logging.loggerInitialized();
    
    final CurrentRoutees r = CurrentRoutees.getInstance();
    final NoRouter nr = NoRouter.getInstance();
    final FromConfig fc = FromConfig.getInstance();
  }

  @Test
  public void mustBeAbleToCreateActorRefFromClass() {
    ActorRef ref = system.actorOf(Props.create(JavaAPITestActor.class));
    assertNotNull(ref);
  }

  @Test
  public void mustBeAbleToCreateActorRefFromFactory() {
    ActorRef ref = system.actorOf(Props.empty().withCreator(new Creator<Actor>() {
      public Actor create() {
        return new JavaAPITestActor();
      }
    }));
    assertNotNull(ref);
  }
}
