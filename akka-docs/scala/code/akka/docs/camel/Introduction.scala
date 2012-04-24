package akka.docs.camel

import akka.actor._
import akka.camel._

//#Consumer-mina
import akka.actor.Actor
import akka.actor.Actor._
import akka.camel.{CamelMessage, Consumer}

class MyActor extends Consumer {
  def endpointUri = "mina:tcp://localhost:6200?textline=true"

  def receive = {
    case msg: CamelMessage => { /* ... */}
    case _            => { /* ... */}
  }
}

// start and expose actor via tcp
val sys = ActorSystem("camel")
val myActor = sys.actorOf(Props[MyActor])
//#Consumer-mina


//#Consumer
class MyActor extends Consumer {
  def endpointUri = "jetty:http://localhost:8877/example"

  def receive = {
    case msg: CamelMessage => { /* ... */}
    case _            => { /* ... */}
  }
}
//#Consumer

//#Producer
import akka.actor.Actor
import akka.camel.{Producer, Oneway}

class MyActor extends Actor with Producer with Oneway {
  def endpointUri = "jms:queue:example"
}
//#Producer