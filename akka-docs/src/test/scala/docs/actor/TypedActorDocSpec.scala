/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.actor

//#imports
import java.lang.String.{ valueOf => println }

import akka.actor.{ ActorContext, ActorRef, TypedActor, TypedProps }
import akka.routing.RoundRobinGroup
import akka.testkit._

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
//#imports

//Mr funny man avoids printing to stdout AND keeping docs alright
import java.lang.String.{ valueOf => println }

//#typed-actor-iface
trait Squarer {
  //#typed-actor-iface-methods
  def squareDontCare(i: Int): Unit //fire-forget

  def square(i: Int): Future[Int] //non-blocking send-request-reply

  def squareNowPlease(i: Int): Option[Int] //blocking send-request-reply

  def squareNow(i: Int): Int //blocking send-request-reply

  @throws(classOf[Exception]) //declare it or you will get an UndeclaredThrowableException
  def squareTry(i: Int): Int //blocking send-request-reply with possible exception
  //#typed-actor-iface-methods
}
//#typed-actor-iface

//#typed-actor-impl
class SquarerImpl(val name: String) extends Squarer {

  def this() = this("default")
  //#typed-actor-impl-methods
  def squareDontCare(i: Int): Unit = i * i //Nobody cares :(

  def square(i: Int): Future[Int] = Future.successful(i * i)

  def squareNowPlease(i: Int): Option[Int] = Some(i * i)

  def squareNow(i: Int): Int = i * i

  def squareTry(i: Int): Int = throw new Exception("Catch me!")
  //#typed-actor-impl-methods
}
//#typed-actor-impl
//#typed-actor-supercharge
trait Foo {
  def doFoo(times: Int): Unit = println("doFoo(" + times + ")")
}

trait Bar {
  def doBar(str: String): Future[String] =
    Future.successful(str.toUpperCase)
}

class FooBar extends Foo with Bar
//#typed-actor-supercharge

//#typed-router-types
trait HasName {
  def name(): String
}

class Named extends HasName {
  import scala.util.Random
  private val id = Random.nextInt(1024)

  def name(): String = "name-" + id
}
//#typed-router-types

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
      case e: Exception => //dun care
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

    Await.result(fSquare, 3.seconds) should be(100)

    oSquare should be(Some(100))

    iSquare should be(100)

    //#typed-actor-stop
    TypedActor(system).stop(mySquarer)
    //#typed-actor-stop

    //#typed-actor-poisonpill
    TypedActor(system).poisonPill(otherSquarer)
    //#typed-actor-poisonpill
  }

  "proxy any ActorRef" in {
    val actorRefToRemoteActor: ActorRef = system.deadLetters
    //#typed-actor-remote
    val typedActor: Foo with Bar =
      TypedActor(system).typedActorOf(TypedProps[FooBar], actorRefToRemoteActor)
    //Use "typedActor" as a FooBar
    //#typed-actor-remote
  }

  "create hierarchies" in {
    try {
      //#typed-actor-hierarchy
      //Inside your Typed Actor
      val childSquarer: Squarer =
        TypedActor(TypedActor.context).typedActorOf(TypedProps[SquarerImpl]())
      //Use "childSquarer" as a Squarer
      //#typed-actor-hierarchy
    } catch {
      case e: Exception => //ignore
    }
  }

  "supercharge" in {
    //#typed-actor-supercharge-usage
    val awesomeFooBar: Foo with Bar =
      TypedActor(system).typedActorOf(TypedProps[FooBar]())

    awesomeFooBar.doFoo(10)
    val f = awesomeFooBar.doBar("yes")

    TypedActor(system).poisonPill(awesomeFooBar)
    //#typed-actor-supercharge-usage
    Await.result(f, 3.seconds) should be("YES")
  }

  "typed router pattern" in {
    //#typed-router
    def namedActor(): HasName = TypedActor(system).typedActorOf(TypedProps[Named]())

    // prepare routees
    val routees: List[HasName] = List.fill(5) { namedActor() }
    val routeePaths = routees.map { r =>
      TypedActor(system).getActorRefFor(r).path.toStringWithoutAddress
    }

    // prepare untyped router
    val router: ActorRef = system.actorOf(RoundRobinGroup(routeePaths).props())

    // prepare typed proxy, forwarding MethodCall messages to `router`
    val typedRouter: HasName =
      TypedActor(system).typedActorOf(TypedProps[Named](), actorRef = router)

    println("actor was: " + typedRouter.name()) // name-184
    println("actor was: " + typedRouter.name()) // name-753
    println("actor was: " + typedRouter.name()) // name-320
    println("actor was: " + typedRouter.name()) // name-164
    //#typed-router

    routees.foreach { TypedActor(system).poisonPill(_) }
    TypedActor(system).poisonPill(router)
  }
}
