/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.actor

//#imports
import akka.dispatch.{ Promise, Future, Await }
import akka.util.duration._
import akka.actor.{ ActorContext, TypedActor, TypedProps }

//#imports

import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers
import akka.testkit._

//#typed-actor-iface
trait Squarer {
  //#typed-actor-iface-methods
  def squareDontCare(i: Int): Unit //fire-forget

  def square(i: Int): Future[Int] //non-blocking send-request-reply

  def squareNowPlease(i: Int): Option[Int] //blocking send-request-reply

  def squareNow(i: Int): Int //blocking send-request-reply
  //#typed-actor-iface-methods
}
//#typed-actor-iface

//#typed-actor-impl
class SquarerImpl(val name: String) extends Squarer {

  def this() = this("default")
  //#typed-actor-impl-methods
  import TypedActor.dispatcher //So we can create Promises

  def squareDontCare(i: Int): Unit = i * i //Nobody cares :(

  def square(i: Int): Future[Int] = Promise successful i * i

  def squareNowPlease(i: Int): Option[Int] = Some(i * i)

  def squareNow(i: Int): Int = i * i
  //#typed-actor-impl-methods
}
//#typed-actor-impl
import java.lang.String.{ valueOf ⇒ println } //Mr funny man avoids printing to stdout AND keeping docs alright
//#typed-actor-supercharge
trait Foo {
  def doFoo(times: Int): Unit = println("doFoo(" + times + ")")
}

trait Bar {
  import TypedActor.dispatcher //So we have an implicit dispatcher for our Promise
  def doBar(str: String): Future[String] = Promise successful str.toUpperCase
}

class FooBar extends Foo with Bar
//#typed-actor-supercharge

class TypedActorDocSpec extends AkkaSpec(Map("akka.loglevel" -> "INFO")) {

  "get the TypedActor extension" in {
    val someReference: AnyRef = null

    try {
      //#typed-actor-extension-tools

      import akka.actor.TypedActor

      //Returns the Typed Actor Extension
      val extension = TypedActor(system) //system is an instance of ActorSystem

      //Returns whether the reference is a Typed Actor Proxy or not
      TypedActor(system).isTypedActor(someReference)

      //Returns the backing Akka Actor behind an external Typed Actor Proxy
      TypedActor(system).getActorRefFor(someReference)

      //Returns the current ActorContext,
      // method only valid within methods of a TypedActor implementation
      val c: ActorContext = TypedActor.context

      //Returns the external proxy of the current Typed Actor,
      // method only valid within methods of a TypedActor implementation
      val s: Squarer = TypedActor.self[Squarer]

      //Returns a contextual instance of the Typed Actor Extension
      //this means that if you create other Typed Actors with this,
      //they will become children to the current Typed Actor.
      TypedActor(TypedActor.context)

      //#typed-actor-extension-tools
    } catch {
      case e: Exception ⇒ //dun care
    }
  }

  "create a typed actor" in {
    //#typed-actor-create1
    val mySquarer: Squarer =
      TypedActor(system).typedActorOf(TypedProps[SquarerImpl]())
    //#typed-actor-create1
    //#typed-actor-create2
    val otherSquarer: Squarer =
      TypedActor(system).typedActorOf(TypedProps(classOf[Squarer], new SquarerImpl("foo")), "name")
    //#typed-actor-create2

    //#typed-actor-calls
    //#typed-actor-call-oneway
    mySquarer.squareDontCare(10)
    //#typed-actor-call-oneway

    //#typed-actor-call-future
    val fSquare = mySquarer.square(10) //A Future[Int]
    //#typed-actor-call-future

    //#typed-actor-call-option
    val oSquare = mySquarer.squareNowPlease(10) //Option[Int]
    //#typed-actor-call-option

    //#typed-actor-call-strict
    val iSquare = mySquarer.squareNow(10) //Int
    //#typed-actor-call-strict
    //#typed-actor-calls

    Await.result(fSquare, 3 seconds) must be === 100

    oSquare must be === Some(100)

    iSquare must be === 100

    //#typed-actor-stop
    TypedActor(system).stop(mySquarer)
    //#typed-actor-stop

    //#typed-actor-poisonpill
    TypedActor(system).poisonPill(otherSquarer)
    //#typed-actor-poisonpill
  }

  "supercharge" in {
    //#typed-actor-supercharge-usage
    val awesomeFooBar: Foo with Bar = TypedActor(system).typedActorOf(TypedProps[FooBar]())

    awesomeFooBar.doFoo(10)
    val f = awesomeFooBar.doBar("yes")

    TypedActor(system).poisonPill(awesomeFooBar)
    //#typed-actor-supercharge-usage
    Await.result(f, 3 seconds) must be === "YES"
  }
}
