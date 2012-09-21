package docs.camel.sample.quartz;
//#QuartzExample
import akka.actor.ActorSystem;
import akka.actor.Props;

public class QuartzSample {
  public static void main(String[] args) {
    ActorSystem system = ActorSystem.create("my-quartz-system");
    system.actorOf(new Props(MyQuartzActor.class));
  }
}
//#QuartzExample