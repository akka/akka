/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.CommandHandlerBuilder;
import akka.persistence.typed.javadsl.EventHandler;
import akka.persistence.typed.javadsl.EventSourcedBehavior;

public class AccountExample extends EventSourcedBehavior<AccountExample.AccountCommand, AccountExample.AccountEvent, AccountExample.Account> {

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

  interface AccountEvent {}
  public static class AccountCreated implements AccountEvent {}
  public static class Deposited implements AccountEvent {
    public final double amount;

    Deposited(double amount) {
      this.amount = amount;
    }
  }
  public static class Withdrawn implements AccountEvent {
    public final double amount;

    Withdrawn(double amount) {
      this.amount = amount;
    }
  }
  public static class AccountClosed implements AccountEvent {}

  interface Account {}
  public static class EmptyAccount implements Account {}
  public static class OpenedAccount implements Account {
    public final double balance;

    OpenedAccount(double balance) {
      this.balance = balance;
    }
  }
  public static class ClosedAccount implements Account {}

  public static Behavior<AccountCommand> behavior(String accountNumber) {
    return Behaviors.setup(context -> new AccountExample(context, accountNumber));
  }

  public AccountExample(ActorContext<AccountCommand> context, String accountNumber) {
    super(new PersistenceId(accountNumber));
  }

  @Override
  public Account emptyState() {
    return new EmptyAccount();
  }

  private CommandHandlerBuilder<AccountCommand, AccountEvent, EmptyAccount, Account> initialHandler() {
    return commandHandlerBuilder(EmptyAccount.class)
      .matchCommand(CreateAccount.class, (__, cmd) -> Effect().persist(new AccountCreated()));
  }

  private CommandHandlerBuilder<AccountCommand, AccountEvent, OpenedAccount, Account> openedAccountHandler() {
    return commandHandlerBuilder(OpenedAccount.class)
      .matchCommand(Deposit.class, (__, cmd) -> Effect().persist(new Deposited(cmd.amount)))
      .matchCommand(Withdraw.class, (acc, cmd) -> {
        if ((acc.balance - cmd.amount) < 0.0) {
          return Effect().unhandled(); // TODO replies are missing in this example
        } else {
          return Effect().persist(new Withdrawn(cmd.amount))
            .thenRun(acc2 -> { // FIXME in scaladsl it's named thenRun, change javadsl also?
              // we know this cast is safe, but somewhat ugly
              OpenedAccount openAccount = (OpenedAccount) acc2;
              // do some side-effect using balance
              System.out.println(openAccount.balance);
            });
        }
      })
      .matchCommand(CloseAccount.class, (acc, cmd) -> {
        if (acc.balance == 0.0)
          return Effect().persist(new AccountClosed());
        else
          return Effect().unhandled();
        });
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

  @Override
  public EventHandler<Account, AccountEvent> eventHandler() {
    return eventHandlerBuilder()
      .matchEvent(AccountCreated.class, EmptyAccount.class, (__, ___) ->
          new OpenedAccount(0.0))
      .matchEvent(Deposited.class, OpenedAccount.class, (acc, cmd) ->
          new OpenedAccount(acc.balance + cmd.amount))
      .matchEvent(Withdrawn.class, OpenedAccount.class, (acc, cmd) ->
          new OpenedAccount(acc.balance - cmd.amount))
      .matchEvent(AccountClosed.class, OpenedAccount.class, (acc, cmd) ->
          new ClosedAccount())
    .build();
  }


}
