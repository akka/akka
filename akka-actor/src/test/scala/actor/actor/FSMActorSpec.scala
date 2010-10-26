/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test

import org.multiverse.api.latches.StandardLatch

import java.util.concurrent.TimeUnit

object FSMActorSpec {

  val unlockedLatch = new StandardLatch
  val lockedLatch = new StandardLatch
  val unhandledLatch = new StandardLatch

  class Lock(code: String, timeout: Int) extends Actor with FSM[String, CodeState] {

    inState("locked") {
      case Event(digit: Char, CodeState(soFar, code)) => {
        soFar + digit match {
          case incomplete if incomplete.length < code.length =>
            stay using CodeState(incomplete, code)
          case codeTry if (codeTry == code) => {
            doUnlock
            goto("open") using CodeState("", code) until timeout
          }
          case wrong => {
            log.error("Wrong code %s", wrong)
            stay using CodeState("", code)
          }
        }
      }
      case Event("hello", _) => stay replying "world"
    }

    inState("open") {
      case Event(StateTimeout, stateData) => {
        doLock
        goto("locked")
      }
    }

    setInitialState("locked", CodeState("", code))
    
    whenUnhandled {
      case Event(_, stateData) => {
        log.info("Unhandled")
        unhandledLatch.open
        stay
      }
    }

    private def doLock() {
      log.info("Locked")
      lockedLatch.open
    }

    private def doUnlock = {
      log.info("Unlocked")
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
    val lock = Actor.actorOf(new Lock("33221", 1000)).start

    lock ! '3'
    lock ! '3'
    lock ! '2'
    lock ! '2'
    lock ! '1'

    assert(unlockedLatch.tryAwait(1, TimeUnit.SECONDS))
    assert(lockedLatch.tryAwait(2, TimeUnit.SECONDS))

    lock ! "not_handled"
    assert(unhandledLatch.tryAwait(2, TimeUnit.SECONDS))

    val answerLatch = new StandardLatch
    object Go
    val tester = Actor.actorOf(new Actor {
      protected def receive = {
        case Go => lock ! "hello"
        case "world" => answerLatch.open

      }
    }).start
    tester ! Go
    assert(answerLatch.tryAwait(2, TimeUnit.SECONDS))
  }
}

