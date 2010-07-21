package sample.osgi

import se.scalablesolutions.akka.actor.Actor


class OSGiActor extends Actor {
    def receive = {
        case msg: String =>
            println("Got message: " + msg)
    }
}

