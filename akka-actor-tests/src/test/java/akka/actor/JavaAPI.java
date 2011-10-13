package akka.actor;

import akka.AkkaApplication;
import akka.japi.Creator;
import org.junit.Test;
import akka.actor.Actors;
import akka.remote.RemoteSupport;
import static org.junit.Assert.*;

public class JavaAPI {

  private AkkaApplication app = new AkkaApplication();

  @Test void mustBeAbleToUseUntypedActor() {
      final RemoteSupport remote = app.remote();
      assertNotNull(remote);
  }

  @Test void mustBeAbleToCreateActorRefFromClass() {
      ActorRef ref = app.createActor(JavaAPITestActor.class);
      assertNotNull(ref);
  }

  @Test void mustBeAbleToCreateActorRefFromFactory() {
      ActorRef ref = app.createActor(new Props().withCreator(new Creator<Actor>() {
          public Actor create() {
              return new JavaAPITestActor();
          }
      }));
      assertNotNull(ref);
  }

  @Test void mustAcceptSingleArgTryTell() {
    ActorRef ref = app.createActor(JavaAPITestActor.class);
    ref.tryTell("hallo");
    ref.tryTell("hallo", ref);
  }
}
