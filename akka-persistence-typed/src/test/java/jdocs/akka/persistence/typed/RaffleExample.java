/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.CommandHandlerBuilder;
import akka.persistence.typed.javadsl.EventHandler;
import akka.persistence.typed.javadsl.PersistentBehavior;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Based on
 * https://github.com/fun-cqrs/fun-cqrs/blob/develop/samples/raffle/src/main/scala/raffle/domain/model/Raffle.scala
 *
 * but in a Java OO style
 */
public class RaffleExample {

  static class RaffleId {}

  interface Event {}
  static final class RaffleStarted implements Event { }
  static final class ParticipantAdded implements Event {
    public final String name;
    public ParticipantAdded(String name) {
      this.name = name;
    }
  }
  static final class ParticipantRemoved implements Event {
    public final String name;
    public ParticipantRemoved(String name) {
      this.name = name;
    }
  }
  static final class RaffleCompleted implements Event {}

  interface Command {}
  static final class StartRaffle implements Command { }

  static final class AddParticipant implements Command {
    public final String name;
    public AddParticipant(String name) {
      this.name = name;
    }
  }

  static final class RemoveParticipant implements Command {
    public final String name;
    public RemoveParticipant(String name) {
      this.name = name;
    }
  }
  static final class CompleteRaffle implements Command { }

  enum RaffleState {
    NOT_STARTED,
    RUNNING,
    COMPLETED
  }

  // mutable domain model impl, completely free from Akka stuff
  static class Raffle {
    private RaffleState state = RaffleState.NOT_STARTED;
    private boolean finished = false;
    private List<String> participants = new ArrayList<>();
    private Optional<String> winner = Optional.empty();

    public RaffleState getState() {
      return state;
    }

    public boolean containsParticipant(String name) {
      return participants.contains(name);
    }

    public void start() {
      state = RaffleState.RUNNING;
    }

    public void addParticipant(String name) {
      participants.add(name);
    }

    public void removeParticipant(String name) {
      participants.remove(name);
    }

    public void complete() {
      state = RaffleState.COMPLETED;
      int winnderIndex = new Random().nextInt(participants.size() - 1);
      winner = Optional.of(participants.get(winnderIndex));
    }

  };


  public static Behavior<Command> behavior(String raffleId) {
    return new PersistentBehavior<Command, Event, Raffle>(new PersistenceId("Raffle|" + raffleId)) {

      @Override
      public Raffle emptyState() {
        return new Raffle();
      }

      @Override
      public CommandHandler<Command, Event, Raffle> commandHandler() {
        CommandHandlerBuilder<Command, Event, Raffle, Raffle> notStartedHandler =
            commandHandlerBuilder(raffle -> raffle.getState() == RaffleState.NOT_STARTED)
                .matchCommand(StartRaffle.class, (raffle, cmd) -> Effect().persist(new RaffleStarted()));

        CommandHandlerBuilder<Command, Event, Raffle, Raffle> runningHandler =
            commandHandlerBuilder(raffle -> raffle.getState() == RaffleState.RUNNING)
                .matchCommand(AddParticipant.class, ((raffle, addParticipant) -> {
                  if (!raffle.containsParticipant(addParticipant.name))
                    return Effect().persist(new ParticipantAdded(addParticipant.name));
                  else
                    return Effect().none();
                })).matchCommand(RemoveParticipant.class, ((raffle, removeParticipant) -> {
              if (raffle.containsParticipant(removeParticipant.name))
                return Effect().persist(new ParticipantRemoved(removeParticipant.name));
              else
                return Effect().none();
            })).matchCommand(CompleteRaffle.class, ((raffle, complete) -> {
              return Effect().persist(new RaffleCompleted())
                  .andThen((completedRaffle) -> {
                    System.out.println("The winner is: " + completedRaffle.winner.get());
                  });
            }));

        CommandHandlerBuilder<Command, Event, Raffle, Raffle> completedHandler =
            commandHandlerBuilder(raffle -> raffle.getState() == RaffleState.COMPLETED);

        return notStartedHandler.orElse(runningHandler).orElse(completedHandler).build();
      }

      @Override
      public EventHandler<Raffle, Event> eventHandler() {
        return eventHandlerBuilder()
            .matchEvent(RaffleStarted.class, (raffle, event) -> {
              raffle.start();
              return raffle;
            })
            .matchEvent(ParticipantAdded.class, (raffle, add) -> {
              raffle.addParticipant(add.name);
              return raffle;
            })
            .matchEvent(ParticipantRemoved.class, (raffle, remove) -> {
              raffle.removeParticipant(remove.name);
              return raffle;
            })
            .matchEvent(RaffleCompleted.class, (raffle, event) -> {
              raffle.complete();
              return raffle;
            })
            .build();
      }
    };
  }

  public static void main(String[] args) {
    ActorSystem<Command> system = ActorSystem.create(behavior("id-1"), "raffle");

    system.tell(new AddParticipant("konrad")); // not started yet
    system.tell(new StartRaffle());
    system.tell(new AddParticipant("patrik"));
    system.tell(new AddParticipant("chris"));
    system.tell(new AddParticipant("johan"));
    system.tell(new StartRaffle()); // not possible
    system.tell(new CompleteRaffle());
  }


}
