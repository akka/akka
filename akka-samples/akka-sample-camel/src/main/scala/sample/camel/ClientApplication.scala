/**
  * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>.
  */

// CAMEL IS NOT PART OF MILESTONE 1 OF AKKA 2.0
// TODO FIXME 2.0

//package sample.camel
//
//import akka.actor.Actor._
//import akka.actor.TypedActor
//import akka.camel.Message
//
///**
// * @author Martin Krasser
// */
//object ClientApplication extends App {
//
//  /* TODO: fix remote example
//
//  val actor1 = remote.actorOf[RemoteActor1]("localhost", 7777)
//  val actor2 = remote.actorFor("remote2", "localhost", 7777)
//
//  val typedActor1 =
//    TypedActor.newRemoteInstance(classOf[RemoteTypedConsumer1],classOf[RemoteTypedConsumer1Impl], "localhost", 7777)
//
//  val typedActor2 = remote.typedActorFor(classOf[RemoteTypedConsumer2], "remote3", "localhost", 7777)
//
//  println(actor1 !! Message("actor1")) // activates and publishes actor remotely
//  println(actor2 !! Message("actor2")) // actor already activated and published remotely
//
//  println(typedActor1.foo("x1", "y1")) // activates and publishes typed actor methods remotely
//  println(typedActor2.foo("x2", "y2")) // typed actor methods already activated and published remotely
//
//  */
//}
