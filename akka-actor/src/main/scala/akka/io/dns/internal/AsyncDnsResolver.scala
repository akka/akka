/*
 * Copyright (C) 2018-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.net.{ Inet4Address, Inet6Address, InetAddress, InetSocketAddress }

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success, Try }

import akka.actor.{ Actor, ActorLogging, ActorRef, ActorRefFactory, Props, ReceiveTimeout, Stash, Status }
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.io.SimpleDnsCache
import akka.io.dns._
import akka.io.dns.CachePolicy.{ CachePolicy, Never, Ttl }
import akka.io.dns.DnsProtocol.{ Ip, RequestType, Srv }
import akka.io.dns.internal.DnsClient._
import akka.pattern.{ ask, pipe }
import akka.pattern.AskTimeoutException
import akka.util.{ Helpers, Timeout }
import akka.util.PrettyDuration._

/**
 * INTERNAL API
 */
@InternalApi
private[io] final class AsyncDnsResolver(
    settings: DnsSettings,
    cache: SimpleDnsCache,
    clientFactory: (ActorRefFactory, List[InetSocketAddress]) => List[ActorRef])
    extends Actor
    with ActorLogging {

  import AsyncDnsResolver._

  // avoid ever looking up localhost by pre-populating cache
  {
    val loopback = InetAddress.getLoopbackAddress
    val (ipv4Address, ipv6Address) = loopback match {
      case ipv6: Inet6Address => (InetAddress.getByName("127.0.0.1"), ipv6)
      case ipv4: Inet4Address => (ipv4, InetAddress.getByName("::1"))
      case unknown            => throw new IllegalArgumentException(s"Loopback address was [$unknown]")
    }
    cache.put(
      "localhost" -> Ip(),
      DnsProtocol.Resolved("localhost", ARecord("localhost", Ttl.effectivelyForever, loopback) :: Nil),
      Ttl.effectivelyForever)
    cache.put(
      "localhost" -> Ip(ipv6 = false, ipv4 = true),
      DnsProtocol.Resolved("localhost", ARecord("localhost", Ttl.effectivelyForever, ipv4Address) :: Nil),
      Ttl.effectivelyForever)
    cache.put(
      "localhost" -> Ip(ipv6 = true, ipv4 = false),
      DnsProtocol.Resolved("localhost", ARecord("localhost", Ttl.effectivelyForever, ipv6Address) :: Nil),
      Ttl.effectivelyForever)

  }

  // For ask to DNS Client
  implicit val timeout: Timeout = Timeout(settings.ResolveTimeout)

  val nameServers = settings.NameServers

  val positiveCachePolicy = settings.PositiveCachePolicy
  val negativeCachePolicy = settings.NegativeCachePolicy
  log.debug(
    "Using name servers [{}] and search domains [{}] with ndots={}",
    nameServers,
    settings.SearchDomains,
    settings.NDots)

  private val resolvers: List[ActorRef] = clientFactory(context, nameServers)

  private val requestIdInjector: ActorRef = context.actorOf(injectorProps, "requestIdInjector")

  // only supports DnsProtocol, not the deprecated Dns protocol
  // AsyncDnsManager converts between the protocols to support the deprecated protocol
  override def receive: Receive = {
    case DnsProtocol.Resolve(name, mode) =>
      cache.get((name, mode)) match {
        case Some(resolved) =>
          log.debug("{} cached {}", mode, resolved)
          sender() ! resolved
        case None =>
          // check if we're being asked to resolve an IP address, in which case no need to do anything async
          if (isInetAddress(name)) {
            Try {
              InetAddress.getByName(name) match { // only a validity check, doesn't do blocking I/O
                case ipv4: Inet4Address => ARecord(name, Ttl.effectivelyForever, ipv4)
                case ipv6: Inet6Address => AAAARecord(name, Ttl.effectivelyForever, ipv6)
                case unexpected         => throw new IllegalArgumentException(s"Unexpected address: $unexpected")
              }
            }.fold(ex => { sender() ! Status.Failure(ex) }, record => {
              val resolved = DnsProtocol.Resolved(name, record :: Nil)
              cache.put(name -> mode, resolved, record.ttl)
              sender() ! resolved
            })
          } else if (resolvers.isEmpty) {
            sender() ! Status.Failure(failToResolve(name, nameServers))
          } else {
            // spawn an actor to manage this resolution (apply search names, failover to other resolvers, etc.)
            val resolutionActor =
              context.actorOf(DnsResolutionActor.props(settings, requestIdInjector, name, mode, sender(), self))
            resolutionActor ! DnsResolutionActor.ResolveWithFirstResolver(resolvers)
          }
      }

    case CachePut(name, mode, resolved, ttl) =>
      cache.put(name -> mode, resolved, ttl)
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object AsyncDnsResolver {

  private val ipv4Address =
    """^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$""".r

  private val ipv6Address =
    """^\s*((([0-9A-Fa-f]{1,4}:){7}([0-9A-Fa-f]{1,4}|:))|(([0-9A-Fa-f]{1,4}:){6}(:[0-9A-Fa-f]{1,4}|((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){5}(((:[0-9A-Fa-f]{1,4}){1,2})|:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){4}(((:[0-9A-Fa-f]{1,4}){1,3})|((:[0-9A-Fa-f]{1,4})?:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){3}(((:[0-9A-Fa-f]{1,4}){1,4})|((:[0-9A-Fa-f]{1,4}){0,2}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){2}(((:[0-9A-Fa-f]{1,4}){1,5})|((:[0-9A-Fa-f]{1,4}){0,3}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){1}(((:[0-9A-Fa-f]{1,4}){1,6})|((:[0-9A-Fa-f]{1,4}){0,4}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(:(((:[0-9A-Fa-f]{1,4}){1,7})|((:[0-9A-Fa-f]{1,4}){0,5}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:)))(%.+)?\s*$""".r

  private[akka] def isIpv4Address(name: String): Boolean =
    ipv4Address.findAllMatchIn(name).nonEmpty

  private[akka] def isIpv6Address(name: String): Boolean =
    ipv6Address.findAllMatchIn(name).nonEmpty

  private def isInetAddress(name: String): Boolean =
    isIpv4Address(name) || isIpv6Address(name)

  private val Empty =
    Future.successful(Answer(-1, immutable.Seq.empty[ResourceRecord], immutable.Seq.empty[ResourceRecord]))

  private def failToResolve(name: String, nameServers: List[InetSocketAddress]): ResolveFailedException =
    ResolveFailedException(s"Failed to resolve $name with nameservers ${nameServers}")

  case class ResolveFailedException(msg: String) extends Exception(msg)

  private final case class CachePut(name: String, mode: RequestType, resolved: DnsProtocol.Resolved, ttl: CachePolicy)

  private final case class DnsQuestionPreInjection(resolver: ActorRef, inject: Short => DnsQuestion, timeout: Timeout)

  private val injectorProps = Props(new RequestIdInjector())

  // tracks and injects a request ID before forwarding to the resolver
  private class RequestIdInjector extends Actor {
    private var requestId: Short = 0

    private def nextId(): Short = {
      requestId = (requestId + 1).toShort
      requestId
    }

    override def receive: Receive = {
      case DnsQuestionPreInjection(resolver, inject, timeout) =>
        implicit val tmout = timeout
        val question = inject(nextId())
        val forwardAnswerTo = sender()
        val result = (resolver ? question).mapTo[Answer]

        result.onComplete {
          case Success(answer) => forwardAnswerTo ! answer
          case Failure(_)      => resolver ! DropRequest(question.id)
        }(ExecutionContexts.parasitic)
    }
  }

  private object DnsResolutionActor {
    def props(
        settings: DnsSettings,
        requestIdInjector: ActorRef,
        name: String,
        mode: RequestType,
        answerTo: ActorRef,
        cacheActor: ActorRef): Props =
      Props(new DnsResolutionActor(settings, requestIdInjector, name, mode, answerTo, cacheActor))

    final case class ResolveWithFirstResolver(remainingResolvers: List[ActorRef])
    final case class Resolve(searchName: String, resolver: ActorRef)
  }

  private class DnsResolutionActor(
      settings: DnsSettings,
      requestIdInjector: ActorRef,
      name: String,
      mode: RequestType,
      answerTo: ActorRef,
      cacheActor: ActorRef)
      extends Actor
      with ActorLogging
      with Stash {
    import DnsResolutionActor._

    private implicit val timeout: Timeout = Timeout(settings.ResolveTimeout)

    private def failToResolve(): Unit =
      answerWithFailure(AsyncDnsResolver.failToResolve(name, settings.NameServers))

    private def answerWithFailure(ex: Throwable): Unit = answer(Status.Failure(ex))

    private def answer(withMsg: Any): Unit = {
      answerTo ! withMsg

      context.stop(self)
    }

    // the fully-qualified domain names (FQDNs), in the order we want to attempt for any given resolver
    private val namesToResolve = {
      val nameWithSearch = settings.SearchDomains.map(sd => s"${name}.${sd}")

      // ndots is a heuristic used to attempt to work out if `name` is an FQDN or a name relative to a search
      // name.  The goal is to not incur the cost of a lookup that's unlikely to resolve because we need to
      // relativize it.  If the name being searched contains less than ndots dots, then we look it up last,
      // rather than first.
      if (name.count(_ == '.') >= settings.NDots) {
        // assume fully-qualified, so try `name` first
        (name :: nameWithSearch).map(Helpers.toRootLowerCase)
      } else {
        // assume relative, so try `name` last
        (nameWithSearch :+ name).map(Helpers.toRootLowerCase)
      }
    }

    override def receive: Receive = notActivelyResolving

    private val notActivelyResolving: Receive = {
      case resolved: DnsProtocol.Resolved                   => handleResolved(resolved)
      case ResolveWithFirstResolver(remainingResolvers)     => nextResolver(remainingResolvers)
      case DnsResolutionActor.Resolve(searchName, resolver) => startResolution(searchName, resolver)
    }

    private def activelyResolving(searchName: String): Receive = {
      // stash resolution requests because we won't do anything with them while actively resolving
      case _: DnsResolutionActor.Resolve => stash()
      case _: ResolveWithFirstResolver   => stash()

      case resolved: DnsProtocol.Resolved =>
        // Check whether this is the final resolution or we need to unstash and try another resolution
        log.info("resolved {}", resolved)
        if (resolved.records.isEmpty) {
          unstashAll()
          context.become(resolveNextName(resolved))
        } else {
          handleResolved(resolved)
        }

      case Status.Failure(ex) =>
        ex match {
          case _: AskTimeoutException =>
            log.info("Resolve of {} timed out after {}.  Trying next name server", searchName, timeout.duration.pretty)
          case _ =>
            log.info("Resolve of {} failed.  Trying next name server.  Exception: {}", name, ex.getMessage)
        }

        unstashAll()
        context.setReceiveTimeout(settings.ResolveTimeout)
        context.become(nextNameServer)
    }

    // will not move on to the next server, since we have a fallback resolution
    private def resolveNextName(fallbackResolution: DnsProtocol.Resolved): Receive = {
      case _: ResolveWithFirstResolver => handleResolved(fallbackResolution)

      case resolve: DnsResolutionActor.Resolve =>
        // Handle the resolution
        startResolution(resolve.searchName, resolve.resolver)
    }

    // drop pending resolves for the current resolver until we get a switch to the next resolver
    private val nextNameServer: Receive = {
      case _: DnsResolutionActor.Resolve => () // do nothing aka drop it
      case switchResolver: ResolveWithFirstResolver =>
        context.setReceiveTimeout(Duration.Undefined)
        nextResolver(switchResolver.remainingResolvers)
        context.become(notActivelyResolving)

      case ReceiveTimeout =>
        // shouldn't happen
        log.warning("Expected there to be a stashed ResolveWithFirstResolver message, but there wasn't")
        failToResolve()
    }

    private def handleResolved(resolved: DnsProtocol.Resolved): Unit = {
      if (resolved.records.nonEmpty) {
        val minTtl = (settings.PositiveCachePolicy +: resolved.records.map(_.ttl)).min
        cacheActor ! CachePut(name, mode, resolved, minTtl)
      } else if (settings.NegativeCachePolicy != Never) {
        cacheActor ! CachePut(name, mode, resolved, settings.NegativeCachePolicy)
      }

      log.debug("{} resolved {}", mode, resolved)

      answer(resolved) // stops this actor
    }

    private def startResolution(searchName: String, resolver: ActorRef): Unit = {
      log.debug("Attempting to resolve {} with {}", searchName, resolver)

      implicit val ec = context.dispatcher // for pipeTo
      resolvedFut(searchName, resolver).pipeTo(self)

      context.become(activelyResolving(searchName))
    }

    private def nextResolver(resolvers: List[ActorRef]): Unit = {
      resolvers.headOption match {
        case Some(resolver) =>
          // send a Resolve message for each of the search names and then a ResolveWithFirstResolver to
          // signal that those were all the names.  All but the first Resolve will be stashed by `activelyResolving`
          namesToResolve.foreach { searchName =>
            self ! DnsResolutionActor.Resolve(searchName, resolver)
          }

          self ! ResolveWithFirstResolver(resolvers.tail)

        case None => failToResolve() // exhausted the resolvers
      }
    }

    private def sendQuestion(resolver: ActorRef, inject: Short => DnsQuestion): Future[Answer] =
      (requestIdInjector ? DnsQuestionPreInjection(resolver, inject, timeout)).mapTo[Answer]

    private def resolvedFut(searchName: String, resolver: ActorRef): Future[DnsProtocol.Resolved] =
      mode match {
        case Ip(ipv4, ipv6) =>
          val ipv4Recs =
            if (ipv4) sendQuestion(resolver, requestId => Question4(requestId, searchName))
            else Empty

          val ipv6Recs =
            if (ipv6) sendQuestion(resolver, requestId => Question6(requestId, searchName))
            else Empty

          ipv4Recs.flatMap { v4 =>
            ipv6Recs.map { v6 =>
              DnsProtocol.Resolved(searchName, v4.rrs ++ v6.rrs, v4.additionalRecs ++ v6.additionalRecs)
            }(ExecutionContexts.parasitic)
          }(ExecutionContexts.parasitic)

        case Srv =>
          sendQuestion(resolver, requestId => SrvQuestion(requestId, searchName)).map { answer =>
            DnsProtocol.Resolved(searchName, answer.rrs, answer.additionalRecs)
          }(ExecutionContexts.parasitic)
      }
  }
}
