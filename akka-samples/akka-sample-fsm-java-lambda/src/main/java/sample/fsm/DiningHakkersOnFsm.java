package sample.fsm;

import akka.actor.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import static java.util.concurrent.TimeUnit.*;
import static sample.fsm.Messages.*;

// Akka adaptation of
// http://www.dalnefre.com/wp/2010/08/dining-philosophers-in-humus/

public class DiningHakkersOnFsm {
  /**
   * Some states the chopstick can be in
   */
  public static enum CS {
    Available,
    Taken
  }

  /**
   * Some state container for the chopstick
   */
  public static final class TakenBy {
    public final ActorRef hakker;
    public TakenBy(ActorRef hakker){
      this.hakker = hakker;
    }
  }

  /*
  * A chopstick is an actor, it can be taken, and put back
  */
  public static class Chopstick extends AbstractLoggingFSM<CS, TakenBy> {
    {
    // A chopstick begins its existence as available and taken by no one
    startWith(CS.Available, new TakenBy(context().system().deadLetters()));

    // When a chopstick is available, it can be taken by a some hakker
    when(CS.Available,
      matchEventEquals(Take, (take, data) ->
        goTo(CS.Taken).using(new TakenBy(sender())).replying(new Taken(self()))));

    // When a chopstick is taken by a hakker
    // It will refuse to be taken by other hakkers
    // But the owning hakker can put it back
    when(CS.Taken,
      matchEventEquals(Take, (take, data) ->
        stay().replying(new Busy(self()))).
      event((event, data) -> (event == Put) && (data.hakker == sender()), (event, data) ->
        goTo(CS.Available).using(new TakenBy(context().system().deadLetters()))));

    // Initialize the chopstick
    initialize();
    }
  }

  /**
  * Some fsm hakker states
  */
  public static enum HS {
    Waiting,
    Thinking,
    Hungry,
    WaitForOtherChopstick,
    FirstChopstickDenied,
    Eating
  }

  /**
  * Some state container to keep track of which chopsticks we have
  */
  public static final class TakenChopsticks {
    public final ActorRef left;
    public final ActorRef right;

    public TakenChopsticks(ActorRef left, ActorRef right) {
      this.left = left;
      this.right = right;
    }
  }

  /*
  * A fsm hakker is an awesome dude or dudette who either thinks about hacking or has to eat ;-)
  */
  public static class Hakker extends AbstractLoggingFSM<HS, TakenChopsticks> {
    private String name;
    private ActorRef left;
    private ActorRef right;

    public Hakker(String name, ActorRef left, ActorRef right) {
      this.name = name;
      this.left = left;
      this.right = right;
    }

    {
    //All hakkers start waiting
    startWith(HS.Waiting, new TakenChopsticks(null, null));

    when(HS.Waiting,
      matchEventEquals(Think, (think, data) -> {
        System.out.println(String.format("%s starts to think", name));
        return startThinking(Duration.create(5, SECONDS));
    }));

    //When a hakker is thinking it can become hungry
    //and try to pick up its chopsticks and eat
    when(HS.Thinking,
      matchEventEquals(StateTimeout(), (event, data) -> {
        left.tell(Take, self());
        right.tell(Take, self());
        return goTo(HS.Hungry);
    }));

    // When a hakker is hungry it tries to pick up its chopsticks and eat
    // When it picks one up, it goes into wait for the other
    // If the hakkers first attempt at grabbing a chopstick fails,
    // it starts to wait for the response of the other grab
    when(HS.Hungry,
      matchEvent(Taken.class, (taken, data) -> taken.chopstick == left,
        (taken, data) -> goTo(HS.WaitForOtherChopstick).using(new TakenChopsticks(left, null))).
      event(Taken.class, (taken, data) -> taken.chopstick == right,
        (taken, data) -> goTo(HS.WaitForOtherChopstick).using(new TakenChopsticks(null, right))).
      event(Busy.class,
        (busy, data) -> goTo(HS.FirstChopstickDenied)));

    // When a hakker is waiting for the last chopstick it can either obtain it
    // and start eating, or the other chopstick was busy, and the hakker goes
    // back to think about how he should obtain his chopsticks :-)
    when(HS.WaitForOtherChopstick,
      matchEvent(Taken.class,
        (taken, data) -> (taken.chopstick == left && data.left == null && data.right != null),
        (taken, data) -> startEating(left, right)).
      event(Taken.class,
        (taken, data) -> (taken.chopstick == right && data.left != null && data.right == null),
        (taken, data) -> startEating(left, right)).
      event(Busy.class, (busy, data) -> {
        if (data.left != null) left.tell(Put, self());
        if (data.right != null) right.tell(Put, self());
        return startThinking(Duration.create(10, MILLISECONDS));
      }));

    // When the results of the other grab comes back,
    // he needs to put it back if he got the other one.
    // Then go back and think and try to grab the chopsticks again
    when(HS.FirstChopstickDenied,
      matchEvent(Taken.class, (taken, data) -> {
        taken.chopstick.tell(Put, self());
        return startThinking(Duration.create(10, MILLISECONDS));
      }).
      event(Busy.class, (busy, data) ->
        startThinking(Duration.create(10, MILLISECONDS))));

    // When a hakker is eating, he can decide to start to think,
    // then he puts down his chopsticks and starts to think
    when(HS.Eating,
      matchEventEquals(StateTimeout(), (event, data) -> {
        left.tell(Put, self());
        right.tell(Put, self());
        System.out.println(String.format("%s puts down his chopsticks and starts to think", name));
        return startThinking(Duration.create(5, SECONDS));
      }));

    // Initialize the hakker
    initialize();
    }

    private FSM.State<HS, TakenChopsticks> startEating(ActorRef left, ActorRef right) {
      System.out.println(String.format("%s has picked up %s and %s and starts to eat",
        name, left.path().name(), right.path().name()));
      return goTo(HS.Eating).using(new TakenChopsticks(left, right)).forMax(Duration.create(5, SECONDS));
    }

    private FSM.State<HS, TakenChopsticks> startThinking(FiniteDuration duration) {
      return goTo(HS.Thinking).using(new TakenChopsticks(null, null)).forMax(duration);
    }
  }

  /*
  * Alright, here's our test-harness
  */
  public static void main(String[] args) {
    ActorSystem system = ActorSystem.create();
    //Create 5 chopsticks
    ActorRef[] chopsticks = new ActorRef[5];
    for (int i = 0; i < 5; i++)
      chopsticks[i] = system.actorOf(Props.create(Chopstick.class), "Chopstick" + i);

    //Create 5 awesome hakkers and assign them their left and right chopstick
    List<String> names = Arrays.asList("Ghosh", "Boner", "Klang", "Krasser", "Manie");
    List<ActorRef> hakkers = new ArrayList<>();
    int i = 0;
    for (String name: names) {
      hakkers.add(system.actorOf(Props.create(Hakker.class, name, chopsticks[i], chopsticks[(i + 1) % 5]), name));
      i++;
    }
    //Signal all hakkers that they should start thinking, and watch the show
    hakkers.stream().forEach(hakker -> hakker.tell(Think, ActorRef.noSender()));
  }
}
