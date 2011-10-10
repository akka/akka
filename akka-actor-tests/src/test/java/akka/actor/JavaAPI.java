package akka.actor;

import akka.japi.Creator;
import org.junit.Test;
import akka.actor.Actors;
import akka.remote.RemoteSupport;
import static org.junit.Assert.*;

public class JavaAPI {

  @Test void mustBeAbleToUseUntypedActor() {
      final RemoteSupport remote = Actors.remote();
      assertNotNull(remote);
  }

  @Test void mustBeAbleToCreateActorRefFromClass() {
      ActorRef ref = Actors.actorOf(JavaAPITestActor.class);
      assertNotNull(ref);
  }

  @Test void mustBeAbleToCreateActorRefFromFactory() {
      ActorRef ref = Actors.actorOf(new Creator<Actor>() {
          public Actor create() {
              return new JavaAPITestActor();
          }
      });
      assertNotNull(ref);
  }

  @Test void mustAcceptSingleArgTryTell() {
    ActorRef ref = Actors.actorOf(JavaAPITestActor.class);
    ref.tryTell("hallo");
    ref.tryTell("hallo", ref);
  }
}
