/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.cluster.sharding.typed;

import akka.actor.typed.ActorRef;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.CommandHandlerWithReply;
import akka.persistence.typed.javadsl.CommandHandlerWithReplyBuilder;
import akka.persistence.typed.javadsl.EventHandler;
import akka.persistence.typed.javadsl.EventHandlerBuilder;
import akka.persistence.typed.javadsl.EventSourcedBehaviorWithEnforcedReplies;
import akka.persistence.typed.javadsl.ReplyEffect;
import akka.serialization.jackson.CborSerializable;
import com.fasterxml.jackson.annotation.JsonCreator;

import java.math.BigDecimal;

/**
 * Bank account example illustrating: - different state classes representing the lifecycle of the
 * account - mutable state - event handlers that delegate to methods in the state classes - command
 * handlers that delegate to methods in the EventSourcedBehavior class - replies of various types,
 * using EventSourcedBehaviorWithEnforcedReplies
 */
public interface AccountExampleWithMutableState {

  // #account-entity
  public class AccountEntity
      extends EventSourcedBehaviorWithEnforcedReplies<
          AccountEntity.Command, AccountEntity.Event, AccountEntity.Account> {

    public static final EntityTypeKey<Command> ENTITY_TYPE_KEY =
        EntityTypeKey.create(Command.class, "Account");

    // Command
    interface Command<Reply> extends CborSerializable {}

    public static class CreateAccount implements Command<OperationResult> {
      public final ActorRef<OperationResult> replyTo;

      @JsonCreator
      public CreateAccount(ActorRef<OperationResult> replyTo) {
        this.replyTo = replyTo;
      }
    }

    public static class Deposit implements Command<OperationResult> {
      public final BigDecimal amount;
      public final ActorRef<OperationResult> replyTo;

      public Deposit(BigDecimal amount, ActorRef<OperationResult> replyTo) {
        this.replyTo = replyTo;
        this.amount = amount;
      }
    }

    public static class Withdraw implements Command<OperationResult> {
      public final BigDecimal amount;
      public final ActorRef<OperationResult> replyTo;

      public Withdraw(BigDecimal amount, ActorRef<OperationResult> replyTo) {
        this.amount = amount;
        this.replyTo = replyTo;
      }
    }

    public static class GetBalance implements Command<CurrentBalance> {
      public final ActorRef<CurrentBalance> replyTo;

      @JsonCreator
      public GetBalance(ActorRef<CurrentBalance> replyTo) {
        this.replyTo = replyTo;
      }
    }

    public static class CloseAccount implements Command<OperationResult> {
      public final ActorRef<OperationResult> replyTo;

      @JsonCreator
      public CloseAccount(ActorRef<OperationResult> replyTo) {
        this.replyTo = replyTo;
      }
    }

    // Reply
    interface CommandReply extends CborSerializable {}

    interface OperationResult extends CommandReply {}

    enum Confirmed implements OperationResult {
      INSTANCE
    }

    public static class Rejected implements OperationResult {
      public final String reason;

      @JsonCreator
      public Rejected(String reason) {
        this.reason = reason;
      }
    }

    public static class CurrentBalance implements CommandReply {
      public final BigDecimal balance;

      @JsonCreator
      public CurrentBalance(BigDecimal balance) {
        this.balance = balance;
      }
    }

    // Event
    interface Event extends CborSerializable {}

    public enum AccountCreated implements Event {
      INSTANCE
    }

    public static class Deposited implements Event {
      public final BigDecimal amount;

      @JsonCreator
      Deposited(BigDecimal amount) {
        this.amount = amount;
      }
    }

    public static class Withdrawn implements Event {
      public final BigDecimal amount;

      @JsonCreator
      Withdrawn(BigDecimal amount) {
        this.amount = amount;
      }
    }

    public static class AccountClosed implements Event {}

    // State
    interface Account extends CborSerializable {}

    public static class EmptyAccount implements Account {
      OpenedAccount openedAccount() {
        return new OpenedAccount();
      }
    }

    public static class OpenedAccount implements Account {
      private BigDecimal balance = BigDecimal.ZERO;

      public BigDecimal getBalance() {
        return balance;
      }

      void makeDeposit(BigDecimal amount) {
        balance = balance.add(amount);
      }

      boolean canWithdraw(BigDecimal amount) {
        return (balance.subtract(amount).compareTo(BigDecimal.ZERO) >= 0);
      }

      void makeWithdraw(BigDecimal amount) {
        if (!canWithdraw(amount))
          throw new IllegalStateException("Account balance can't be negative");
        balance = balance.subtract(amount);
      }

      ClosedAccount closedAccount() {
        return new ClosedAccount();
      }
    }

    public static class ClosedAccount implements Account {}

    public static AccountEntity create(String accountNumber, PersistenceId persistenceId) {
      return new AccountEntity(accountNumber, persistenceId);
    }

    private final String accountNumber;

    private AccountEntity(String accountNumber, PersistenceId persistenceId) {
      super(persistenceId);
      this.accountNumber = accountNumber;
    }

    @Override
    public Account emptyState() {
      return new EmptyAccount();
    }

    @Override
    public CommandHandlerWithReply<Command, Event, Account> commandHandler() {
      CommandHandlerWithReplyBuilder<Command, Event, Account> builder =
          newCommandHandlerWithReplyBuilder();

      builder.forStateType(EmptyAccount.class).onCommand(CreateAccount.class, this::createAccount);

      builder
          .forStateType(OpenedAccount.class)
          .onCommand(Deposit.class, this::deposit)
          .onCommand(Withdraw.class, this::withdraw)
          .onCommand(GetBalance.class, this::getBalance)
          .onCommand(CloseAccount.class, this::closeAccount);

      builder
          .forStateType(ClosedAccount.class)
          .onAnyCommand(() -> Effect().unhandled().thenNoReply());

      return builder.build();
    }

    private ReplyEffect<Event, Account> createAccount(EmptyAccount account, CreateAccount command) {
      return Effect()
          .persist(AccountCreated.INSTANCE)
          .thenReply(command.replyTo, account2 -> Confirmed.INSTANCE);
    }

    private ReplyEffect<Event, Account> deposit(OpenedAccount account, Deposit command) {
      return Effect()
          .persist(new Deposited(command.amount))
          .thenReply(command.replyTo, account2 -> Confirmed.INSTANCE);
    }

    private ReplyEffect<Event, Account> withdraw(OpenedAccount account, Withdraw command) {
      if (!account.canWithdraw(command.amount)) {
        return Effect()
            .reply(command.replyTo, new Rejected("not enough funds to withdraw " + command.amount));
      } else {
        return Effect()
            .persist(new Withdrawn(command.amount))
            .thenReply(command.replyTo, account2 -> Confirmed.INSTANCE);
      }
    }

    private ReplyEffect<Event, Account> getBalance(OpenedAccount account, GetBalance command) {
      return Effect().reply(command.replyTo, new CurrentBalance(account.balance));
    }

    private ReplyEffect<Event, Account> closeAccount(OpenedAccount account, CloseAccount command) {
      if (account.getBalance().equals(BigDecimal.ZERO)) {
        return Effect()
            .persist(new AccountClosed())
            .thenReply(command.replyTo, account2 -> Confirmed.INSTANCE);
      } else {
        return Effect()
            .reply(command.replyTo, new Rejected("balance must be zero for closing account"));
      }
    }

    @Override
    public EventHandler<Account, Event> eventHandler() {
      EventHandlerBuilder<Account, Event> builder = newEventHandlerBuilder();

      builder
          .forStateType(EmptyAccount.class)
          .onEvent(AccountCreated.class, (account, event) -> account.openedAccount());

      builder
          .forStateType(OpenedAccount.class)
          .onEvent(
              Deposited.class,
              (account, deposited) -> {
                account.makeDeposit(deposited.amount);
                return account;
              })
          .onEvent(
              Withdrawn.class,
              (account, withdrawn) -> {
                account.makeWithdraw(withdrawn.amount);
                return account;
              })
          .onEvent(AccountClosed.class, (account, closed) -> account.closedAccount());

      return builder.build();
    }
  }

  // #account-entity
}
