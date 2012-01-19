/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.extension

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

//#imports
import akka.actor._
import java.util.concurrent.atomic.AtomicLong

//#imports

//#extension
class CountExtensionImpl extends Extension {
  //Since this Extension is a shared instance
  // per ActorSystem we need to be threadsafe
  private val counter = new AtomicLong(0)

  //This is the operation this Extension provides
  def increment() = counter.incrementAndGet()
}
//#extension

//#extensionid
object CountExtension
  extends ExtensionId[CountExtensionImpl]
  with ExtensionIdProvider {
  //The lookup method is required by ExtensionIdProvider,
  // so we return ourselves here, this allows us
  // to configure our extension to be loaded when
  // the ActorSystem starts up
  override def lookup = CountExtension

  //This method will be called by Akka
  // to instantiate our Extension
  override def createExtension(system: ActorSystemImpl) = new CountExtensionImpl
}
//#extensionid

//#extension-usage-actor
import akka.actor.Actor

class MyActor extends Actor {
  def receive = {
    case someMessage ⇒
      CountExtension(context.system).increment()
  }
}
//#extension-usage-actor

//#extension-usage-actor-trait
import akka.actor.Actor

trait Counting { self: Actor ⇒
  def increment() = CountExtension(context.system).increment()
}
class MyCounterActor extends Actor with Counting {
  def receive = {
    case someMessage ⇒ increment()
  }
}
//#extension-usage-actor-trait

class ExtensionDocSpec extends WordSpec with MustMatchers {

  "demonstrate how to create an extension in Scala" in {
    val system: ActorSystem = null
    intercept[Exception] {
      //#extension-usage
      CountExtension(system).increment
      //#extension-usage
    }
  }

}
