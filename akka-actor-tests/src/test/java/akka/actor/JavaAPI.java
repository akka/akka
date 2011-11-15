package akka.actor;

import akka.actor.ActorSystem;
import akka.japi.Creator;
import org.junit.Test;
import akka.actor.Actors;
import akka.remote.RemoteSupport;
import static org.junit.Assert.*;

public class JavaAPI {

  private ActorSystem app = new ActorSystem();

  @Test void mustBeAbleToCreateActorRefFromClass() {
      ActorRef ref = app.actorOf(JavaAPITestActor.class);
      assertNotNull(ref);
  }

  @Test void mustBeAbleToCreateActorRefFromFactory() {
      ActorRef ref = app.actorOf(new Props().withCreator(new Creator<Actor>() {
          public Actor create() {
              return new JavaAPITestActor();
          }
      }));
      assertNotNull(ref);
  }

  @Test void mustAcceptSingleArgTell() {
    ActorRef ref = app.actorOf(JavaAPITestActor.class);
    ref.tell("hallo");
    ref.tell("hallo", ref);
  }
}
