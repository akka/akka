package sample.camel.quartz;

import akka.actor.ActorSystem;
import akka.actor.Props;

public class QuartzSample {
  public static void main(String[] args) {
    ActorSystem system = ActorSystem.create("my-quartz-system");
    system.actorOf(Props.create(MyQuartzActor.class));
  }
}
