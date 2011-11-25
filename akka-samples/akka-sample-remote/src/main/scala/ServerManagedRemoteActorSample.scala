/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

// REMOTING IS NOT PART OF MILESTONE 1 OF AKKA 2.0

//package sample.remote
//
//import akka.actor.Actor._
//import akka.actor. {ActorRegistry, Actor}
//
//class HelloWorldActor extends Actor {
//  def receive = {
//    case "Hello" =>
//      reply("World")
//  }
//}
//
//object ServerManagedRemoteActorServer {
//
//  def run = {
//    Actor.remote.start("localhost", 2552)
//    Actor.remote.register("hello-service", actorOf[HelloWorldActor])
//  }
//
//  def main(args: Array[String]) = run
//}
//
//object ServerManagedRemoteActorClient {
//
//  def run = {
//    val actor = Actor.remote.actorFor("hello-service", "localhost", 2552)
//    val result = actor !! "Hello"
//  }
//
//  def main(args: Array[String]) = run
//}
//
