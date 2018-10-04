/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.actor.typed.BackoffSupervisorStrategy;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.*;

public class AccountExample2 extends EventApplyingPersistentBehavior<AccountExample2.AccountCommand, AccountExample2.AccountEvent, AccountExample2.Account> {

  interface AccountCommand {}
  public static class CreateAccount implements AccountCommand {}
  public static class Deposit implements AccountCommand {
    public final double amount;

    public Deposit(double amount) {
      this.amount = amount;
    }
  }
  public static class Withdraw implements AccountCommand {
    public final double amount;

    public Withdraw(double amount) {
      this.amount = amount;
    }
  }
  public static class CloseAccount implements AccountCommand {}

  interface AccountEvent extends Event<Account> {}
  public static class AccountCreated implements AccountEvent {
    @Override
    public Account applyTo(Account state) {
      return state.created(this);
    }
  }
  public static class Deposited implements AccountEvent {
    public final double amount;

    Deposited(double amount) {
      this.amount = amount;
    }

    @Override
    public Account applyTo(Account state) {
      return state.deposited(this);
    }
  }
  public static class Withdrawn implements AccountEvent {
    public final double amount;

    Withdrawn(double amount) {
      this.amount = amount;
    }

    @Override
    public Account applyTo(Account state) {
      return state.withdrawn(this);
    }
  }
  public static class AccountClosed implements AccountEvent {
    @Override
    public Account applyTo(Account state) {
      return state.closed(this);
    }
  }

  interface Account {
    default Account created(AccountCreated event) {
      throw new IllegalStateException();
    }

    default Account deposited(Deposited event) {
      throw new IllegalStateException();
    }

    default Account withdrawn(Withdrawn event) {
      throw new IllegalStateException();
    }

    default Account closed(AccountClosed event) {
      throw new IllegalStateException();
    }
  }

  public enum EmptyAccount implements Account {
    INSTANCE;

    @Override
    public Account created(AccountCreated event) {
      return new OpenedAccount(0.0);
    }
  }

  public static class OpenedAccount implements Account {
    public final double balance;

    OpenedAccount(double balance) {
      this.balance = balance;
    }

    @Override
    public Account deposited(Deposited event) {
      return new OpenedAccount(balance + event.amount);
    }

    @Override
    public Account withdrawn(Withdrawn event) {
      return new OpenedAccount(balance - event.amount);
    }

    @Override
    public Account closed(AccountClosed event) {
      return ClosedAccount.INSTANCE;
    }

    private boolean canWithdraw(double amount) {
      return (balance - amount) >= 0.0;
    }

    private boolean isCloseable() {
      return balance == 0.0;
    }
  }

  public enum ClosedAccount implements Account {
    INSTANCE
  }

  public static Behavior<AccountCommand> behavior(String accountNumber) {
    return Behaviors.setup(context -> new AccountExample2(context, accountNumber));
  }

  public AccountExample2(ActorContext<AccountCommand> context, String accountNumber) {
    super(new PersistenceId(accountNumber));
  }

  @Override
  public Account emptyState() {
    return EmptyAccount.INSTANCE;
  }

  private CommandHandlerBuilder<AccountCommand, AccountEvent, EmptyAccount, Account> initialHandler() {
    return commandHandlerBuilder(EmptyAccount.class)
      .matchCommand(CreateAccount.class, this::create);
  }

  private CommandHandlerBuilder<AccountCommand, AccountEvent, OpenedAccount, Account> openedAccountHandler() {
    return commandHandlerBuilder(OpenedAccount.class)
        .matchCommand(Deposit.class, this::deposit)
        .matchCommand(Withdraw.class, this::withdraw)
        .matchCommand(CloseAccount.class, this::closeAccount);
  }

  private CommandHandlerBuilder<AccountCommand, AccountEvent, ClosedAccount, Account> closedHandler() {
    return commandHandlerBuilder(ClosedAccount.class)
        .matchCommand(AccountCommand.class, (__, ___) -> Effect().unhandled());
  }

  @Override
  public CommandHandler<AccountCommand, AccountEvent, Account> commandHandler() {
    return initialHandler()
        .orElse(openedAccountHandler())
        .orElse(closedHandler())
        .build();
  }

  private Effect<AccountEvent, Account> create(EmptyAccount account, CreateAccount command) {
    return Effect().persist(new AccountCreated());
  }

  private Effect<AccountEvent, Account> deposit(OpenedAccount account, Deposit command) {
    return Effect().persist(new Deposited(command.amount));
  }

  private Effect<AccountEvent, Account> withdraw(OpenedAccount account, Withdraw command) {
    if (!account.canWithdraw(command.amount)) {
      return Effect().unhandled(); // TODO replies are missing in this example
    } else {
      return Effect().persist(new Withdrawn(command.amount))
        .andThen(acc2 -> { // FIXME in scaladsl it's named thenRun, change javadsl also?
          // we know this cast is safe, but somewhat ugly
          OpenedAccount openAccount = (OpenedAccount) acc2;
          // do some side-effect using balance
          System.out.println(openAccount.balance);
        });
    }
  }

  private Effect<AccountEvent, Account> closeAccount(OpenedAccount account, CloseAccount command) {
    if (account.isCloseable())
      return Effect().persist(new AccountClosed());
    else
      return Effect().unhandled();
  }

}

// We could provide these in the library as conveniences for following this pattern
// TODO: better names?
interface Event<State> {
  State applyTo(State currentState);
}

abstract class EventApplyingPersistentBehavior<Command, E extends Event<State>, State>
    extends PersistentBehavior<Command, E, State> {

  public EventApplyingPersistentBehavior(PersistenceId persistenceId) {
    super(persistenceId);
  }

  public EventApplyingPersistentBehavior(PersistenceId persistenceId, BackoffSupervisorStrategy backoffSupervisorStrategy) {
    super(persistenceId, backoffSupervisorStrategy);
  }

  @Override
  public final EventHandler<State, E> eventHandler() {
    return (state, event) -> event.applyTo(state); // double-dispatch via event
  }

}
