/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.sharding.typed

//#test
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.typed.PersistenceId
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpecLike

//#test

import docs.akka.cluster.sharding.typed.AccountExampleWithEventHandlersInState.AccountEntity

//#test
//#testkit
class AccountExampleDocSpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorTestKit.config)
    //#testkit
    with AnyWordSpecLike
    with BeforeAndAfterEach
    with LogCapturing {

  private val eventSourcedTestKit =
    EventSourcedBehaviorTestKit[AccountEntity.Command[_], AccountEntity.Event, AccountEntity.Account](
      system,
      AccountEntity("1", PersistenceId("Account", "1")))

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit.clear()
  }

  "Account" must {

    "be created with zero balance" in {
      val result = eventSourcedTestKit.runCommand[AccountEntity.OperationResult](AccountEntity.CreateAccount(_))
      result.reply shouldBe AccountEntity.Confirmed
      result.event shouldBe AccountEntity.AccountCreated
      result.stateOfType[AccountEntity.OpenedAccount].balance shouldBe 0
    }

    "handle Withdraw" in {
      eventSourcedTestKit.runCommand[AccountEntity.OperationResult](AccountEntity.CreateAccount(_))

      val result1 = eventSourcedTestKit.runCommand[AccountEntity.OperationResult](AccountEntity.Deposit(100, _))
      result1.reply shouldBe AccountEntity.Confirmed
      result1.event shouldBe AccountEntity.Deposited(100)
      result1.stateOfType[AccountEntity.OpenedAccount].balance shouldBe 100

      val result2 = eventSourcedTestKit.runCommand[AccountEntity.OperationResult](AccountEntity.Withdraw(10, _))
      result2.reply shouldBe AccountEntity.Confirmed
      result2.event shouldBe AccountEntity.Withdrawn(10)
      result2.stateOfType[AccountEntity.OpenedAccount].balance shouldBe 90
    }

    "reject Withdraw overdraft" in {
      eventSourcedTestKit.runCommand[AccountEntity.OperationResult](AccountEntity.CreateAccount(_))
      eventSourcedTestKit.runCommand[AccountEntity.OperationResult](AccountEntity.Deposit(100, _))

      val result = eventSourcedTestKit.runCommand[AccountEntity.OperationResult](AccountEntity.Withdraw(110, _))
      result.replyOfType[AccountEntity.Rejected]
      result.hasNoEvents shouldBe true
    }

    "handle GetBalance" in {
      eventSourcedTestKit.runCommand[AccountEntity.OperationResult](AccountEntity.CreateAccount(_))
      eventSourcedTestKit.runCommand[AccountEntity.OperationResult](AccountEntity.Deposit(100, _))

      val result = eventSourcedTestKit.runCommand[AccountEntity.CurrentBalance](AccountEntity.GetBalance(_))
      result.reply.balance shouldBe 100
      result.hasNoEvents shouldBe true
    }
  }
}
//#test
