/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.AkkaApplication
import akka.actor.{ Actor, ActorRef, Props }

abstract class AkkaSpec(_application: AkkaApplication = AkkaApplication())
  extends TestKit(_application) with WordSpec with MustMatchers {

  def app = _application

  def actorOf(props: Props): ActorRef = app.createActor(props)

  def actorOf[T <: Actor](clazz: Class[T]): ActorRef = actorOf(Props(clazz))

  def actorOf[T <: Actor: Manifest]: ActorRef = actorOf(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]])

  def actorOf[T <: Actor](factory: ⇒ T): ActorRef = actorOf(Props(factory))
}