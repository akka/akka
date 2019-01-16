/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.*;

public class AccountExample
    extends EventSourcedBehavior<
        AccountExample.AccountCommand, AccountExample.AccountEvent, AccountExample.Account> {

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

  private CommandHandlerBuilderByState<AccountCommand, AccountEvent, EmptyAccount, Account>
      initialCmdHandler() {
    return newCommandHandlerBuilder()
        .forStateType(EmptyAccount.class)
        .matchCommand(CreateAccount.class, (__, cmd) -> Effect().persist(new AccountCreated()));
  }

  private CommandHandlerBuilderByState<AccountCommand, AccountEvent, OpenedAccount, Account>
      openedAccountCmdHandler() {
    return newCommandHandlerBuilder()
        .forStateType(OpenedAccount.class)
        .matchCommand(Deposit.class, (__, cmd) -> Effect().persist(new Deposited(cmd.amount)))
        .matchCommand(
            Withdraw.class,
            (acc, cmd) -> {
              if ((acc.balance - cmd.amount) < 0.0) {
                return Effect().unhandled(); // TODO replies are missing in this example
              } else {
                return Effect()
                    .persist(new Withdrawn(cmd.amount))
                    .thenRun(
                        acc2 -> { // FIXME in scaladsl it's named thenRun, change javadsl also?
                          // we know this cast is safe, but somewhat ugly
                          OpenedAccount openAccount = (OpenedAccount) acc2;
                          // do some side-effect using balance
                          System.out.println(openAccount.balance);
                        });
              }
            })
        .matchCommand(
            CloseAccount.class,
            (acc, cmd) -> {
              if (acc.balance == 0.0) return Effect().persist(new AccountClosed());
              else return Effect().unhandled();
            });
  }

  private CommandHandlerBuilderByState<AccountCommand, AccountEvent, ClosedAccount, Account>
      closedCmdHandler() {
    return newCommandHandlerBuilder()
        .forStateType(ClosedAccount.class)
        .matchCommand(AccountCommand.class, __ -> Effect().unhandled());
  }

  @Override
  public CommandHandler<AccountCommand, AccountEvent, Account> commandHandler() {
    return initialCmdHandler().orElse(openedAccountCmdHandler()).orElse(closedCmdHandler()).build();
  }

  private EventHandlerBuilderByState<EmptyAccount, Account, AccountEvent> initialEvtHandler() {
    return newEventHandlerBuilder()
        .forStateType(EmptyAccount.class)
        .matchEvent(AccountCreated.class, () -> new OpenedAccount(0.0));
  }

  private EventHandlerBuilderByState<OpenedAccount, Account, AccountEvent>
      openedAccountEvtHandler() {
    return newEventHandlerBuilder()
        .forStateType(OpenedAccount.class)
        .matchEvent(Deposited.class, (acc, cmd) -> new OpenedAccount(acc.balance + cmd.amount))
        .matchEvent(Withdrawn.class, (acc, cmd) -> new OpenedAccount(acc.balance - cmd.amount))
        .matchEvent(AccountClosed.class, ClosedAccount::new);
  }

  @Override
  public EventHandler<Account, AccountEvent> eventHandler() {
    return initialEvtHandler().orElse(openedAccountEvtHandler()).build();
  }
}
