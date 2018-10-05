/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.*;

/** 
 * Account example using dynamic dispatch to deliver commands and events to the right handlers.
 */
public class AccountExampleNoBuilders extends EventSourcedBehavior<AccountExampleNoBuilders.AccountCommand, AccountExampleNoBuilders.AccountEvent, AccountExampleNoBuilders.Account> {

    public AccountExampleNoBuilders(String accountNumber) {
        super(new PersistenceId(accountNumber));
    }

    @Override
    public Account emptyState() {
        return new EmptyAccount();
    }

    @Override
    public CommandHandler<AccountCommand, AccountEvent, Account> commandHandler() {
        return Account::applyCommand;
    }

    @Override
    public EventHandler<Account, AccountEvent> eventHandler() {
        return Account::applyEvent;
    }

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

    public static class AccountClosed implements AccountEvent {
    }

    interface Account {
        Effect<AccountEvent, Account> applyCommand(AccountCommand accountCommand);
        Account applyEvent(AccountEvent event);
    }

    public class EmptyAccount implements Account {
        @Override
        public Effect<AccountEvent, Account> applyCommand(AccountCommand cmd) {
            if (cmd instanceof CreateAccount) {
                return Effect().persist(new AccountCreated());
            } else {
                return Effect().unhandled();
            }
        }

        @Override
        public Account applyEvent(AccountEvent event) {
            if (event instanceof AccountCreated)
                return new OpenedAccount(0.0);
            else
                throw new IllegalStateException("Invalid event " + event.getClass());
        }

    }

    public class OpenedAccount implements Account {
        private double balance;

        OpenedAccount(double balance) {
            this.balance = balance;
        }

        @Override
        public Effect<AccountEvent, Account> applyCommand(AccountCommand cmd) {

            if (cmd instanceof Deposit) {
                Deposit deposit = (Deposit) cmd;
                return Effect().persist(new Deposited(deposit.amount));
            }

            if (cmd instanceof Withdraw) {
                Withdraw withdraw = (Withdraw) cmd;
                return (balance - withdraw.amount < 0.0) ?
                        Effect().unhandled() :
                        Effect().persist(new Withdrawn(withdraw.amount));
            }

            if (cmd instanceof CloseAccount) {
                return (balance == 0.0) ?
                        Effect().persist(new AccountClosed()) :
                        Effect().unhandled();
            }

            return Effect().unhandled();

        }


        @Override
        public Account applyEvent(AccountEvent event) {

            if (event instanceof Deposited) {
                Deposited deposited = (Deposited) event;
                return new OpenedAccount(balance + deposited.amount);
            }

            if (event instanceof Withdrawn) {
                Withdrawn withdrawn = (Withdrawn) event;
                return new OpenedAccount(balance - withdrawn.amount);
            }

            if (event instanceof AccountClosed) {
                AccountClosed accountClosed = (AccountClosed) event;
                return new ClosedAccount();
            }

            throw new IllegalStateException("Invalid event " + event.getClass());

        }

    }

    public class ClosedAccount implements Account {
        @Override
        public Effect<AccountEvent, Account> applyCommand(AccountCommand cmd) {
            return Effect().unhandled();
        }

        @Override
        public Account applyEvent(AccountEvent event) {
            throw new IllegalStateException("Invalid event " + event.getClass());
        }
    }
}
