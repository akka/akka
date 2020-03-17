/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.sharding.typed

//#test
import java.util.UUID

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.persistence.typed.PersistenceId
import org.scalatest.wordspec.AnyWordSpecLike

//#test

//#test-events
import akka.persistence.journal.inmem.InmemJournal
import akka.actor.typed.eventstream.EventStream

//#test-events

import docs.akka.cluster.sharding.typed.AccountExampleWithEventHandlersInState.AccountEntity

object AccountExampleDocSpec {
  val inmemConfig =
    //#inmem-config
    """ 
    akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    akka.persistence.journal.inmem.test-serialization = on
    """
  //#inmem-config

  val snapshotConfig =
    //#snapshot-store-config
    s""" 
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/snapshot-${UUID.randomUUID().toString}"
    """
  //#snapshot-store-config
}

//#test
class AccountExampleDocSpec extends ScalaTestWithActorTestKit(s"""
      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
      akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      akka.persistence.snapshot-store.local.dir = "target/snapshot-${UUID.randomUUID().toString}"
    """) with AnyWordSpecLike with LogCapturing {

  "Account" must {

    "handle Withdraw" in {
      val probe = createTestProbe[AccountEntity.OperationResult]()
      val ref = spawn(AccountEntity("1", PersistenceId("Account", "1")))
      ref ! AccountEntity.CreateAccount(probe.ref)
      probe.expectMessage(AccountEntity.Confirmed)
      ref ! AccountEntity.Deposit(100, probe.ref)
      probe.expectMessage(AccountEntity.Confirmed)
      ref ! AccountEntity.Withdraw(10, probe.ref)
      probe.expectMessage(AccountEntity.Confirmed)
    }

    "reject Withdraw overdraft" in {
      val probe = createTestProbe[AccountEntity.OperationResult]()
      val ref = spawn(AccountEntity("2", PersistenceId("Account", "2")))
      ref ! AccountEntity.CreateAccount(probe.ref)
      probe.expectMessage(AccountEntity.Confirmed)
      ref ! AccountEntity.Deposit(100, probe.ref)
      probe.expectMessage(AccountEntity.Confirmed)
      ref ! AccountEntity.Withdraw(110, probe.ref)
      probe.expectMessageType[AccountEntity.Rejected]
    }

    "handle GetBalance" in {
      val opProbe = createTestProbe[AccountEntity.OperationResult]()
      val ref = spawn(AccountEntity("3", PersistenceId("Account", "3")))
      ref ! AccountEntity.CreateAccount(opProbe.ref)
      opProbe.expectMessage(AccountEntity.Confirmed)
      ref ! AccountEntity.Deposit(100, opProbe.ref)
      opProbe.expectMessage(AccountEntity.Confirmed)

      val getProbe = createTestProbe[AccountEntity.CurrentBalance]()
      ref ! AccountEntity.GetBalance(getProbe.ref)
      getProbe.expectMessage(AccountEntity.CurrentBalance(100))
    }

    //#test
    //#test-events
    "store events" in {
      val eventProbe = createTestProbe[InmemJournal.Operation]()
      system.eventStream ! EventStream.Subscribe(eventProbe.ref)

      val probe = createTestProbe[AccountEntity.OperationResult]()
      val ref = spawn(AccountEntity("4", PersistenceId("Account", "4")))
      ref ! AccountEntity.CreateAccount(probe.ref)
      eventProbe.expectMessageType[InmemJournal.Write].event should ===(AccountEntity.AccountCreated)

      ref ! AccountEntity.Deposit(100, probe.ref)
      probe.expectMessage(AccountEntity.Confirmed)
      eventProbe.expectMessageType[InmemJournal.Write].event should ===(AccountEntity.Deposited(100))
    }
    //#test-events
    //#test
  }
}
//#test
