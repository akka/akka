package akka.io

import java.net.{ UnknownHostException, InetAddress }
import java.util.concurrent.TimeUnit

import akka.actor.Actor
import com.typesafe.config.Config

import scala.collection.immutable
import sun.net.{ InetAddressCachePolicy ⇒ IACP }
import akka.util.Helpers.Requiring

class InetAddressDnsResolver(cache: SimpleDnsCache, config: Config) extends Actor {

  import IACP.NEVER // 0 constant

  private def getTtl(path: String, positive: Boolean): Long =
    config.getString(path) match {
      case "default" ⇒
        (if (positive) IACP.get else IACP.getNegative) match {
          case NEVER      ⇒ NEVER
          case n if n > 0 ⇒ TimeUnit.SECONDS.toMillis(n)
          case _          ⇒ Long.MaxValue // forever if negative
        }
      case "forever" ⇒ Long.MaxValue
      case "never"   ⇒ NEVER
      case _ ⇒ config.getDuration(path, TimeUnit.MILLISECONDS)
        .requiring(_ > 0, s"akka.io.dns.$path must be 'default', 'forever', 'never' or positive duration")
    }
  val positiveTtl = getTtl("positive-ttl", true)
  val negativeTtl = getTtl("negative-ttl", false)

  override def receive = {
    case Dns.Resolve(name) ⇒
      val answer = cache.cached(name) match {
        case Some(a) ⇒ a
        case None ⇒
          try {
            val answer = Dns.Resolved(name, InetAddress.getAllByName(name))
            if (positiveTtl != NEVER) cache.put(answer, positiveTtl)
            answer
          } catch {
            case e: UnknownHostException ⇒
              val answer = Dns.Resolved(name, immutable.Seq.empty, immutable.Seq.empty)
              if (negativeTtl != NEVER) cache.put(answer, negativeTtl)
              answer
          }
      }
      sender() ! answer
  }
}
