/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.AkkaApplication
import akka.actor._
import akka.event.Logging
import akka.actor.Status._
import akka.util._
import akka.util.duration._
import akka.util.Helpers._
import akka.actor.DeploymentConfig._
import akka.serialization.Compression.LZF
import akka.remote.RemoteProtocol._
import akka.remote.RemoteProtocol.RemoteSystemDaemonMessageType._

import java.net.InetSocketAddress

import com.eaio.uuid.UUID
import akka.serialization.{ JavaSerializer, Serialization, Serializer, Compression }
import akka.dispatch.{ Terminate, Dispatchers, Future, PinnedDispatcher }

/**
 * Remote module - contains remote client and server config, remote server instance, remote daemon, remote dispatchers etc.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class Remote(val app: AkkaApplication) {

  val log = Logging(app, this)

  import app._
  import app.config
  import app.AkkaConfig._

  val nodename = app.nodename

  // TODO move to AkkaConfig?
  val shouldCompressData = config.getBool("akka.remote.use-compression", false)
  val remoteSystemDaemonAckTimeout = Duration(config.getInt("akka.remote.remote-daemon-ack-timeout", 30), DefaultTimeUnit).toMillis.toInt

  val failureDetector = new AccrualFailureDetector(app)

  //  val gossiper = new Gossiper(this)

  val remoteDaemonServiceName = "akka-system-remote-daemon".intern

  // FIXME configure computeGridDispatcher to what?
  val computeGridDispatcher = dispatcherFactory.newDispatcher("akka:compute-grid").build

  private[remote] lazy val remoteDaemonSupervisor = app.actorOf(Props(
    OneForOneStrategy(List(classOf[Exception]), None, None)), "akka-system-remote-supervisor") // is infinite restart what we want?

  private[remote] lazy val remoteDaemon =
    app.provider.actorOf(
      Props(new RemoteSystemDaemon(this)).withDispatcher(dispatcherFactory.newPinnedDispatcher(remoteDaemonServiceName)),
      remoteDaemonSupervisor,
      remoteDaemonServiceName,
      systemService = true)

  private[remote] lazy val remoteClientLifeCycleHandler = app.actorOf(Props(new Actor {
    def receive = {
      case RemoteClientError(cause, remote, address) ⇒ remote.shutdownClientConnection(address)
      case RemoteClientDisconnected(remote, address) ⇒ remote.shutdownClientConnection(address)
      case _                                         ⇒ //ignore other
    }
  }), "akka.remote.RemoteClientLifeCycleListener")

  lazy val eventStream = new NetworkEventStream(app)

  lazy val server: RemoteSupport = {
    val remote = new akka.remote.netty.NettyRemoteSupport(app)
    remote.start() //TODO FIXME Any application loader here?

    app.mainbus.subscribe(eventStream.sender, classOf[RemoteLifeCycleEvent])
    app.mainbus.subscribe(remoteClientLifeCycleHandler, classOf[RemoteLifeCycleEvent])

    // TODO actually register this provider in app in remote mode
    //provider.register(ActorRefProvider.RemoteProvider, new RemoteActorRefProvider)
    remote
  }

  def start(): Unit = {
    val serverAddress = server.app.defaultAddress //Force init of server
    val daemonAddress = remoteDaemon.address //Force init of daemon
    log.info("Starting remote server on [{}] and starting remoteDaemon with address [{}]", serverAddress, daemonAddress)
  }
}

/**
 * Internal system "daemon" actor for remote internal communication.
 *
 * It acts as the brain of the remote that responds to system remote events (messages) and undertakes action.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class RemoteSystemDaemon(remote: Remote) extends Actor {

  import remote._

  override def preRestart(reason: Throwable, msg: Option[Any]) {
    log.debug("RemoteSystemDaemon failed due to [{}] - restarting...", reason)
  }

  def receive: Actor.Receive = {
    case message: RemoteSystemDaemonMessageProtocol ⇒
      log.debug("Received command [\n{}] to RemoteSystemDaemon on [{}]", message.getMessageType, nodename)

      message.getMessageType match {
        case USE                    ⇒ handleUse(message)
        case RELEASE                ⇒ handleRelease(message)
        // case STOP                   ⇒ cluster.shutdown()
        // case DISCONNECT             ⇒ cluster.disconnect()
        // case RECONNECT              ⇒ cluster.reconnect()
        // case RESIGN                 ⇒ cluster.resign()
        // case FAIL_OVER_CONNECTIONS  ⇒ handleFailover(message)
        case GOSSIP                 ⇒ handleGossip(message)
        case FUNCTION_FUN0_UNIT     ⇒ handle_fun0_unit(message)
        case FUNCTION_FUN0_ANY      ⇒ handle_fun0_any(message)
        case FUNCTION_FUN1_ARG_UNIT ⇒ handle_fun1_arg_unit(message)
        case FUNCTION_FUN1_ARG_ANY  ⇒ handle_fun1_arg_any(message)
        //TODO: should we not deal with unrecognized message types?
      }

    case unknown ⇒ log.warning("Unknown message to RemoteSystemDaemon [{}]", unknown)
  }

  def handleUse(message: RemoteSystemDaemonMessageProtocol) {
    try {
      if (message.hasActorPath) {

        val actorFactoryBytes =
          if (shouldCompressData) LZF.uncompress(message.getPayload.toByteArray) else message.getPayload.toByteArray

        val actorFactory =
          app.serialization.deserialize(actorFactoryBytes, classOf[() ⇒ Actor], None) match {
            case Left(error)     ⇒ throw error
            case Right(instance) ⇒ instance.asInstanceOf[() ⇒ Actor]
          }

        val actorPath = ActorPath(remote.app, message.getActorPath)
        val parent = actorPath.parent.ref

        if (parent.isDefined) {
          app.provider.actorOf(Props(creator = actorFactory), parent.get, actorPath.name)
        } else {
          log.error("Parent actor does not exist, ignoring remote system daemon command [{}]", message)
        }

      } else {
        log.error("Actor 'address' for actor to instantiate is not defined, ignoring remote system daemon command [{}]", message)
      }

      sender ! Success(app.defaultAddress)
    } catch {
      case error: Throwable ⇒ //FIXME doesn't seem sensible
        sender ! Failure(error)
        throw error
    }
  }

  // FIXME implement handleRelease
  def handleRelease(message: RemoteSystemDaemonMessageProtocol) {
  }

  def handleGossip(message: RemoteSystemDaemonMessageProtocol) {
    // try {
    //   val gossip = serialization.deserialize(message.getPayload.toByteArray, classOf[Gossip], None) match {
    //     case Left(error)     ⇒ throw error
    //     case Right(instance) ⇒ instance.asInstanceOf[Gossip]
    //   }

    //   gossiper tell gossip

    //   sender ! Success(address.toString)
    // } catch {
    //   case error: Throwable ⇒
    //     sender ! Failure(error)
    //     throw error
    // }
  }

  // FIXME: handle real remote supervision
  def handle_fun0_unit(message: RemoteSystemDaemonMessageProtocol) {
    new LocalActorRef(app,
      Props(
        context ⇒ {
          case f: Function0[_] ⇒ try { f() } finally { context.self.stop() }
        }).copy(dispatcher = computeGridDispatcher), app.guardian, app.guardian.path / Props.randomName, systemService = true) ! payloadFor(message, classOf[Function0[Unit]])
  }

  // FIXME: handle real remote supervision
  def handle_fun0_any(message: RemoteSystemDaemonMessageProtocol) {
    new LocalActorRef(app,
      Props(
        context ⇒ {
          case f: Function0[_] ⇒ try { sender ! f() } finally { context.self.stop() }
        }).copy(dispatcher = computeGridDispatcher), app.guardian, app.guardian.path / Props.randomName, systemService = true) forward payloadFor(message, classOf[Function0[Any]])
  }

  // FIXME: handle real remote supervision
  def handle_fun1_arg_unit(message: RemoteSystemDaemonMessageProtocol) {
    new LocalActorRef(app,
      Props(
        context ⇒ {
          case (fun: Function[_, _], param: Any) ⇒ try { fun.asInstanceOf[Any ⇒ Unit].apply(param) } finally { context.self.stop() }
        }).copy(dispatcher = computeGridDispatcher), app.guardian, app.guardian.path / Props.randomName, systemService = true) ! payloadFor(message, classOf[Tuple2[Function1[Any, Unit], Any]])
  }

  // FIXME: handle real remote supervision
  def handle_fun1_arg_any(message: RemoteSystemDaemonMessageProtocol) {
    new LocalActorRef(app,
      Props(
        context ⇒ {
          case (fun: Function[_, _], param: Any) ⇒ try { sender ! fun.asInstanceOf[Any ⇒ Any](param) } finally { context.self.stop() }
        }).copy(dispatcher = computeGridDispatcher), app.guardian, app.guardian.path / Props.randomName, systemService = true) forward payloadFor(message, classOf[Tuple2[Function1[Any, Any], Any]])
  }

  def handleFailover(message: RemoteSystemDaemonMessageProtocol) {
    // val (from, to) = payloadFor(message, classOf[(InetSocketremoteDaemonServiceName, InetSocketremoteDaemonServiceName)])
    // cluster.failOverClusterActorRefConnections(from, to)
  }

  private def payloadFor[T](message: RemoteSystemDaemonMessageProtocol, clazz: Class[T]): T = {
    app.serialization.deserialize(message.getPayload.toByteArray, clazz, None) match {
      case Left(error)     ⇒ throw error
      case Right(instance) ⇒ instance.asInstanceOf[T]
    }
  }
}

class RemoteMessage(input: RemoteMessageProtocol, remote: RemoteSupport, classLoader: Option[ClassLoader] = None) {
  lazy val sender: ActorRef =
    if (input.hasSender)
      remote.app.provider.deserialize(
        SerializedActorRef(input.getSender.getHost, input.getSender.getPort, input.getSender.getPath)).getOrElse(throw new IllegalStateException("OHNOES"))
    else
      remote.app.deadLetters

  lazy val recipient: ActorRef = remote.app.actorFor(input.getRecipient.getPath).getOrElse(remote.app.deadLetters)

  lazy val payload: Either[Throwable, AnyRef] =
    if (input.hasException) Left(parseException())
    else Right(MessageSerializer.deserialize(remote.app, input.getMessage, classLoader))

  protected def parseException(): Throwable = {
    val exception = input.getException
    val classname = exception.getClassname
    try {
      val exceptionClass =
        if (classLoader.isDefined) classLoader.get.loadClass(classname) else Class.forName(classname)
      exceptionClass
        .getConstructor(Array[Class[_]](classOf[String]): _*)
        .newInstance(exception.getMessage).asInstanceOf[Throwable]
    } catch {
      case problem: Exception ⇒
        remote.app.mainbus.publish(Logging.Error(problem, remote, problem.getMessage))
        CannotInstantiateRemoteExceptionDueToRemoteProtocolParsingErrorException(problem, classname, exception.getMessage)
    }
  }

  override def toString = "RemoteMessage: " + recipient + "(" + input.getRecipient.getPath + ") from " + sender
}

trait RemoteMarshallingOps {

  def app: AkkaApplication

  def createMessageSendEnvelope(rmp: RemoteMessageProtocol): AkkaRemoteProtocol = {
    val arp = AkkaRemoteProtocol.newBuilder
    arp.setMessage(rmp)
    arp.build
  }

  def createControlEnvelope(rcp: RemoteControlProtocol): AkkaRemoteProtocol = {
    val arp = AkkaRemoteProtocol.newBuilder
    arp.setInstruction(rcp)
    arp.build
  }

  /**
   * Serializes the ActorRef instance into a Protocol Buffers (protobuf) Message.
   */
  def toRemoteActorRefProtocol(actor: ActorRef): ActorRefProtocol = {
    val rep = app.provider.serialize(actor)
    ActorRefProtocol.newBuilder.setHost(rep.hostname).setPort(rep.port).setPath(rep.path).build
  }

  def createRemoteMessageProtocolBuilder(
    recipient: Either[ActorRef, ActorRefProtocol],
    message: Either[Throwable, Any],
    senderOption: Option[ActorRef]): RemoteMessageProtocol.Builder = {

    val messageBuilder = RemoteMessageProtocol.newBuilder.setRecipient(recipient.fold(toRemoteActorRefProtocol _, identity))

    message match {
      case Right(message) ⇒
        messageBuilder.setMessage(MessageSerializer.serialize(app, message.asInstanceOf[AnyRef]))
      case Left(exception) ⇒
        messageBuilder.setException(ExceptionProtocol.newBuilder
          .setClassname(exception.getClass.getName)
          .setMessage(Option(exception.getMessage).getOrElse(""))
          .build)
    }

    if (senderOption.isDefined) messageBuilder.setSender(toRemoteActorRefProtocol(senderOption.get))

    messageBuilder
  }

  def receiveMessage(remoteMessage: RemoteMessage, untrustedMode: Boolean) {
    val recipient = remoteMessage.recipient

    remoteMessage.payload match {
      case Left(t) ⇒ throw t
      case Right(r) ⇒ r match {
        case _: Terminate                              ⇒ if (untrustedMode) throw new SecurityException("RemoteModule server is operating is untrusted mode, can not stop the actor") else recipient.stop()
        case _: AutoReceivedMessage if (untrustedMode) ⇒ throw new SecurityException("RemoteModule server is operating is untrusted mode, can not pass on a AutoReceivedMessage to the remote actor")
        case m                                         ⇒ recipient.!(m)(remoteMessage.sender)
      }
    }
  }
}
