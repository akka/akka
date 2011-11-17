package akka.actor;

import akka.actor.ActorSystem;
import akka.japi.Creator;
import org.junit.Test;
import akka.actor.Actors;
import akka.remote.RemoteSupport;
import static org.junit.Assert.*;

public class JavaAPI {

  private ActorSystem system = ActorSystem.create();

  @Test void mustBeAbleToCreateActorRefFromClass() {
      ActorRef ref = system.actorOf(JavaAPITestActor.class);
      assertNotNull(ref);
  }

  @Test void mustBeAbleToCreateActorRefFromFactory() {
      ActorRef ref = system.actorOf(new Props().withCreator(new Creator<Actor>() {
          public Actor create() {
              return new JavaAPITestActor();
          }
      }));
      assertNotNull(ref);
  }

  @Test void mustAcceptSingleArgTell() {
    ActorRef ref = system.actorOf(JavaAPITestActor.class);
    ref.tell("hallo");
    ref.tell("hallo", ref);
  }
}
