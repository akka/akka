/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.actor.Actor

/**
 * RemoteModule client and server event listener that pipes the events to the standard Akka EventHander.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteEventHandler extends Actor {

  def receive = {

    // client
    case RemoteClientError(cause, client, address) ⇒
      app.eventHandler.error(cause, client, "RemoteClientError - Address[%s]" format address.toString)
    case RemoteClientWriteFailed(request, cause, client, address) ⇒
      app.eventHandler.error(cause, client, "RemoteClientWriteFailed - Request[%s] Address[%s]".format(request, address.toString))
    case RemoteClientDisconnected(client, address) ⇒
      app.eventHandler.info(client, "RemoteClientDisconnected - Address[%s]" format address.toString)
    case RemoteClientConnected(client, address) ⇒
      app.eventHandler.info(client, "RemoteClientConnected - Address[%s]" format address.toString)
    case RemoteClientStarted(client, address) ⇒
      app.eventHandler.info(client, "RemoteClientStarted - Address[%s]" format address.toString)
    case RemoteClientShutdown(client, address) ⇒
      app.eventHandler.info(client, "RemoteClientShutdown - Address[%s]" format address.toString)

    // server
    case RemoteServerError(cause, server) ⇒
      app.eventHandler.error(cause, server, "RemoteServerError")
    case RemoteServerWriteFailed(request, cause, server, clientAddress) ⇒
      app.eventHandler.error(cause, server, "RemoteServerWriteFailed - Request[%s] Address[%s]" format (request, clientAddress.toString))
    case RemoteServerStarted(server) ⇒
      app.eventHandler.info(server, "RemoteServerStarted")
    case RemoteServerShutdown(server) ⇒
      app.eventHandler.info(server, "RemoteServerShutdown")
    case RemoteServerClientConnected(server, clientAddress) ⇒
      app.eventHandler.info(server, "RemoteServerClientConnected - Address[%s]" format clientAddress.toString)
    case RemoteServerClientDisconnected(server, clientAddress) ⇒
      app.eventHandler.info(server, "RemoteServerClientDisconnected - Address[%s]" format clientAddress.toString)
    case RemoteServerClientClosed(server, clientAddress) ⇒
      app.eventHandler.info(server, "RemoteServerClientClosed - Address[%s]" format clientAddress.toString)

    case _ ⇒ //ignore other
  }
}

