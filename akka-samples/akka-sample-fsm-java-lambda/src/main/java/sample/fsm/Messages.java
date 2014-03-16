package sample.fsm;

import akka.actor.ActorRef;

public class Messages {

  public static final class Busy {
    public final ActorRef chopstick;
    public Busy(ActorRef chopstick){
      this.chopstick = chopstick;
    }
  }

  private static interface PutMessage {};
  public static final Object Put = new PutMessage() {
    @Override
    public String toString() { return "Put"; }
  };

  private static interface TakeMessage {};
  public static final Object Take = new TakeMessage() {
    @Override
    public String toString() { return "Take"; }
  };

  public static final class Taken {
    public final ActorRef chopstick;
    public Taken(ActorRef chopstick){
      this.chopstick = chopstick;
    }
  }

  private static interface ThinkMessage {};
  public static final Object Think = new ThinkMessage() {};
}
