/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test

import org.multiverse.api.latches.StandardLatch

import java.util.concurrent.TimeUnit

object FSMActorSpec {
  import FSM._

  val unlockedLatch = new StandardLatch
  val lockedLatch = new StandardLatch
  val unhandledLatch = new StandardLatch
  val terminatedLatch = new StandardLatch
  val transitionLatch = new StandardLatch

  sealed trait LockState
  case object Locked extends LockState
  case object Open extends LockState

  class Lock(code: String, timeout: (Long, TimeUnit)) extends Actor with FSM[LockState, CodeState] {

    notifying {
      case Transition(Locked, Open) => transitionLatch.open
      case Transition(_, _) => ()
    }

    when(Locked) {
      case Event(digit: Char, CodeState(soFar, code)) => {
        soFar + digit match {
          case incomplete if incomplete.length < code.length =>
            stay using CodeState(incomplete, code)
          case codeTry if (codeTry == code) => {
            doUnlock
            goto(Open) using CodeState("", code) forMax timeout
          }
          case wrong => {
            log.slf4j.error("Wrong code {}", wrong)
            stay using CodeState("", code)
          }
        }
      }
      case Event("hello", _) => stay replying "world"
      case Event("bye", _) => stop
    }

    when(Open) {
      case Event(StateTimeout, _) => {
        doLock
        goto(Locked)
      }
    }

    startWith(Locked, CodeState("", code))

    whenUnhandled {
      case Event(_, stateData) => {
        log.slf4j.info("Unhandled")
        unhandledLatch.open
        stay
      }
    }

    onTermination {
      case reason => terminatedLatch.open
    }

    private def doLock() {
      log.slf4j.info("Locked")
      lockedLatch.open
    }

    private def doUnlock = {
      log.slf4j.info("Unlocked")
      unlockedLatch.open
    }
  }

  case class CodeState(soFar: String, code: String)
}

class FSMActorSpec extends JUnitSuite {
  import FSMActorSpec._

  @Test
  def unlockTheLock = {

    // lock that locked after being open for 1 sec
    val lock = Actor.actorOf(new Lock("33221", (1, TimeUnit.SECONDS))).start

    lock ! '3'
    lock ! '3'
    lock ! '2'
    lock ! '2'
    lock ! '1'

    assert(unlockedLatch.tryAwait(1, TimeUnit.SECONDS))
    assert(transitionLatch.tryAwait(1, TimeUnit.SECONDS))
    assert(lockedLatch.tryAwait(2, TimeUnit.SECONDS))

    lock ! "not_handled"
    assert(unhandledLatch.tryAwait(2, TimeUnit.SECONDS))

    val answerLatch = new StandardLatch
    object Hello
    object Bye
    val tester = Actor.actorOf(new Actor {
      protected def receive = {
        case Hello => lock ! "hello"
        case "world" => answerLatch.open
        case Bye => lock ! "bye"
      }
    }).start
    tester ! Hello
    assert(answerLatch.tryAwait(2, TimeUnit.SECONDS))

    tester ! Bye
    assert(terminatedLatch.tryAwait(2, TimeUnit.SECONDS))
  }
}
