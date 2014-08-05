package akka.io

import java.net.{ UnknownHostException, InetAddress }
import java.util.concurrent.TimeUnit

import akka.actor.Actor
import com.typesafe.config.Config

import scala.collection.immutable

class InetAddressDnsResolver(cache: SimpleDnsCache, config: Config) extends Actor {
  val positiveTtl = config.getDuration("positive-ttl", TimeUnit.MILLISECONDS)
  val negativeTtl = config.getDuration("negative-ttl", TimeUnit.MILLISECONDS)

  override def receive = {
    case Dns.Resolve(name) ⇒
      val answer = cache.cached(name) match {
        case Some(a) ⇒ a
        case None ⇒
          try {
            val answer = Dns.Resolved(name, InetAddress.getAllByName(name))
            cache.put(answer, positiveTtl)
            answer
          } catch {
            case e: UnknownHostException ⇒
              val answer = Dns.Resolved(name, immutable.Seq.empty, immutable.Seq.empty)
              cache.put(answer, negativeTtl)
              answer
          }
      }
      sender() ! answer
  }
}
