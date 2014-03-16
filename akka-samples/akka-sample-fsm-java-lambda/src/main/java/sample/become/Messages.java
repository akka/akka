package sample.become;

import akka.actor.ActorRef;

public class Messages {
  public static final class Busy {
    public final ActorRef chopstick;
    public Busy(ActorRef chopstick){
      this.chopstick = chopstick;
    }
  }

  public static final class Put {
    public final ActorRef hakker;
    public Put(ActorRef hakker){
      this.hakker = hakker;
    }
  }

  public static final class Take {
    public final ActorRef hakker;
    public Take(ActorRef hakker){
      this.hakker = hakker;
    }
  }

  public static final class Taken {
    public final ActorRef chopstick;
    public Taken(ActorRef chopstick){
      this.chopstick = chopstick;
    }
  }

  private static interface EatMessage {};
  public static final Object Eat = new EatMessage() {};

  private static interface ThinkMessage {};
  public static final Object Think = new ThinkMessage() {};
}
