/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import scala.util.control.NonFatal
import scala.collection.immutable
import akka.io.Inet
import akka.http.model.{ HttpHeader, Uri, HttpRequest }
import akka.http.server.HttpListener
import akka.http.client._
import akka.actor._

/**
 * The gateway actor into the low-level HTTP layer.
 */
private[http] class HttpManager(httpSettings: HttpExt#Settings) extends Actor with ActorLogging {
  import HttpManager._
  import httpSettings._

  // counters for naming the various sub-actors we create
  private[this] val listenerCounter = Iterator from 0
  private[this] val groupCounter = Iterator from 0
  private[this] val hostConnectorCounter = Iterator from 0
  private[this] val proxyConnectorCounter = Iterator from 0

  // our child actors
  private[this] var settingsGroups = Map.empty[ClientConnectionSettings, ActorRef]
  private[this] var connectors = Map.empty[Http.HostConnectorSetup, ActorRef]
  private[this] var listeners = Seq.empty[ActorRef]

  /////////////////// INITIAL / RUNNING STATE //////////////////////

  def receive = withTerminationManagement {
    case request: HttpRequest ⇒
      try {
        val req = request.withEffectiveUri(securedConnection = false)
        val connector = connectorForUri(req.uri)
        // we never render absolute URIs, also drop any potentially existing fragment
        connector.forward(req.copy(uri = req.uri.toRelative.withoutFragment))
      } catch {
        case NonFatal(e) ⇒
          log.error("Illegal request: {}", e.getMessage)
          sender() ! Status.Failure(e)
      }

    // 3xx Redirect, sent up from one of our HttpHostConnector children for us to
    // forward to the respective HttpHostConnector for the redirection target
    case ctx @ HttpHostConnector.RequestContext(req, _, _, commander) ⇒
      val connector = connectorForUri(req.uri)
      // we never render absolute URIs, also drop any potentially existing fragment
      val newReq = req.copy(uri = req.uri.toRelative.withoutFragment)
      connector.tell(ctx.copy(request = newReq), commander)

    case connect: Http.Connect ⇒
      settingsGroupFor(ClientConnectionSettings(connect.settings)).forward(connect)

    case setup: Http.HostConnectorSetup ⇒
      val connector = connectorFor(setup)
      sender().tell(Http.HostConnectorInfo(connector, setup), connector)

    // we support sending an HttpRequest instance together with a corresponding HostConnectorSetup
    // in once step (rather than sending the setup first and having to wait for the response)
    case (request: HttpRequest, setup: Http.HostConnectorSetup) ⇒
      connectorFor(setup).forward(request)

    case Http.HttpRequestChannelSetup ⇒ sender() ! Http.HttpRequestChannelInfo

    case bind: Http.Bind ⇒
      val commander = sender()
      listeners :+= context.watch {
        context.actorOf(
          props = Props(new HttpListener(commander, bind, httpSettings)) withDispatcher ListenerDispatcher,
          name = "listener-" + listenerCounter.next())
      }

    case cmd: Http.CloseCommand ⇒
      // start triggering an orderly complete shutdown by first closing all outgoing connections 
      shutdownSettingsGroups(cmd, Set(sender()))
  }

  def withTerminationManagement(behavior: Receive): Receive = ({
    case ev @ Terminated(child) ⇒
      if (listeners contains child)
        listeners = listeners filter (_ != child)
      else if (connectors exists (_._2 == child))
        connectors = connectors filter { _._2 != child }
      else
        settingsGroups = settingsGroups filter { _._2 != child }
      behavior.applyOrElse(ev, (_: Terminated) ⇒ ())

    case HttpHostConnector.DemandIdleShutdown ⇒
      val hostConnector = sender()
      var sendPoisonPill = true
      connectors = connectors filter {
        case (x: ProxyConnectorSetup, proxiedConnector) if x.proxyConnector == hostConnector ⇒
          proxiedConnector ! HttpHostConnector.DemandIdleShutdown
          sendPoisonPill = false // the PoisonPill will be sent by the proxiedConnector
          false
        case (_, `hostConnector`) ⇒ false
        case _                    ⇒ true
      }
      if (sendPoisonPill) hostConnector ! PoisonPill
  }: Receive) orElse behavior

  def connectorForUri(uri: Uri) = {
    val host = uri.authority.host
    connectorFor(Http.HostConnectorSetup(host.toString(), uri.effectivePort, sslEncryption = uri.scheme == "https"))
  }

  def connectorFor(setup: Http.HostConnectorSetup) = {
    val normalizedSetup = resolveAutoProxied(setup)
    import Http.ClientConnectionType._
    normalizedSetup.connectionType match {
      case _: Proxied  ⇒ proxiedConnectorFor(normalizedSetup)
      case Direct      ⇒ hostConnectorFor(normalizedSetup)
      case AutoProxied ⇒ throw new IllegalStateException
    }
  }

  def proxiedConnectorFor(normalizedSetup: Http.HostConnectorSetup): ActorRef = {
    val Http.ClientConnectionType.Proxied(proxyHost, proxyPort) = normalizedSetup.connectionType
    val proxyConnector = hostConnectorFor(normalizedSetup.copy(host = proxyHost, port = proxyPort))
    val proxySetup = proxyConnectorSetup(normalizedSetup, proxyConnector)
    def createAndRegisterProxiedConnector = {
      val proxiedConnector = context.actorOf(
        props = Props(new ProxiedHostConnector(normalizedSetup.host, normalizedSetup.port, proxyConnector)),
        name = "proxy-connector-" + proxyConnectorCounter.next())
      connectors = connectors.updated(proxySetup, proxiedConnector)
      context.watch(proxiedConnector)
    }
    connectors.getOrElse(proxySetup, createAndRegisterProxiedConnector)
  }

  def hostConnectorFor(normalizedSetup: Http.HostConnectorSetup): ActorRef = {
    def createAndRegisterHostConnector = {
      val settingsGroup = settingsGroupFor(normalizedSetup.settings.get.connectionSettings) // must not be moved into the Props(...)!
      val hostConnector = context.actorOf(
        props = Props(new HttpHostConnector(normalizedSetup, settingsGroup)) withDispatcher HostConnectorDispatcher,
        name = "host-connector-" + hostConnectorCounter.next())
      connectors = connectors.updated(normalizedSetup, hostConnector)
      context.watch(hostConnector)
    }
    connectors.getOrElse(normalizedSetup, createAndRegisterHostConnector)
  }

  def settingsGroupFor(settings: ClientConnectionSettings): ActorRef = {
    def createAndRegisterSettingsGroup = {
      val group = context.actorOf(
        props = Props(new HttpClientSettingsGroup(settings, httpSettings)) withDispatcher SettingsGroupDispatcher,
        name = "group-" + groupCounter.next())
      settingsGroups = settingsGroups.updated(settings, group)
      context.watch(group)
    }
    settingsGroups.getOrElse(settings, createAndRegisterSettingsGroup)
  }

  /////////////////// ORDERLY SHUTDOWN PROCESS //////////////////////

  def shutdownSettingsGroups(cmd: Http.CloseCommand, commanders: Set[ActorRef]): Unit =
    if (!settingsGroups.isEmpty) {
      settingsGroups.values.foreach(_ ! cmd)
      context.become(closingSettingsGroups(cmd, commanders))
    } else shutdownHostConnectors(cmd, commanders) // if we are done with the outgoing connections, close all host connectors

  def closingSettingsGroups(cmd: Http.CloseCommand, commanders: Set[ActorRef]): Receive =
    withTerminationManagement {
      case _: Http.CloseCommand ⇒ // the first CloseCommand we received has precedence over ones potentially sent later
        context.become(closingSettingsGroups(cmd, commanders + sender()))

      case Terminated(_) ⇒
        if (settingsGroups.isEmpty) // if we are done with the outgoing connections, close all host connectors
          shutdownHostConnectors(cmd, commanders)
        else context.become(closingSettingsGroups(cmd, commanders))
    }

  def shutdownHostConnectors(cmd: Http.CloseCommand, commanders: Set[ActorRef]): Unit =
    if (!connectors.isEmpty) {
      connectors.values.foreach(_ ! cmd)
      context.become(closingConnectors(cmd, commanders))
    } else shutdownListeners(cmd, commanders) // if we are done with the host connectors, close all listeners

  def closingConnectors(cmd: Http.CloseCommand, commanders: Set[ActorRef]): Receive =
    withTerminationManagement {
      case _: Http.CloseCommand ⇒ // the first CloseCommand we received has precedence over ones potentially sent later
        context.become(closingConnectors(cmd, commanders + sender()))

      case Terminated(_) ⇒
        if (connectors.isEmpty) // if we are done with the host connectors, close all listeners
          shutdownListeners(cmd, commanders)
        else context.become(closingConnectors(cmd, commanders))
    }

  def shutdownListeners(cmd: Http.CloseCommand, commanders: Set[ActorRef]): Unit = {
    listeners foreach { x ⇒ x ! cmd }
    context.become(unbinding(cmd, commanders))
    if (listeners.isEmpty) self ! Http.Unbound
  }

  def unbinding(cmd: Http.CloseCommand, commanders: Set[ActorRef]): Receive =
    withTerminationManagement {
      case _: Http.CloseCommand ⇒ // the first CloseCommand we received has precedence over ones potentially sent later
        context.become(unbinding(cmd, commanders + sender()))

      case Terminated(_) ⇒
        if (connectors.isEmpty) {
          // if we are done with the listeners we have completed the full orderly shutdown
          commanders.foreach(_ ! cmd.event)
          context.become(receive)
        } else context.become(unbinding(cmd, commanders))
    }
}

private[http] object HttpManager {
  private class ProxyConnectorSetup(host: String, port: Int, sslEncryption: Boolean,
                                    options: immutable.Traversable[Inet.SocketOption],
                                    settings: Option[HostConnectorSettings], connectionType: Http.ClientConnectionType,
                                    defaultHeaders: immutable.Seq[HttpHeader], val proxyConnector: ActorRef)
    extends Http.HostConnectorSetup(host, port, sslEncryption, options, settings, connectionType, defaultHeaders)

  private def proxyConnectorSetup(normalizedSetup: Http.HostConnectorSetup, proxyConnector: ActorRef) = {
    import normalizedSetup._
    new ProxyConnectorSetup(host, port, sslEncryption, options, settings, connectionType, defaultHeaders, proxyConnector)
  }

  def resolveAutoProxied(setup: Http.HostConnectorSetup)(implicit refFactory: ActorRefFactory) = {
    val normalizedSetup = setup.normalized
    import normalizedSetup._
    val resolved =
      if (sslEncryption) Http.ClientConnectionType.Direct // TODO
      else connectionType match {
        case Http.ClientConnectionType.AutoProxied ⇒
          val scheme = Uri.httpScheme(sslEncryption)
          val proxySettings = settings.get.connectionSettings.proxySettings.get(scheme)
          proxySettings.filter(_.matchesHost(host)) match {
            case Some(ProxySettings(proxyHost, proxyPort, _)) ⇒ Http.ClientConnectionType.Proxied(proxyHost, proxyPort)
            case None                                         ⇒ Http.ClientConnectionType.Direct
          }
        case x ⇒ x
      }
    normalizedSetup.copy(connectionType = resolved)
  }
}