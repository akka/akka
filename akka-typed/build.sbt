import akka.{ AkkaBuild, Formatting }

AkkaBuild.defaultSettings
AkkaBuild.mayChangeSettings
Formatting.formatSettings

disablePlugins(MimaPlugin)

initialCommands := """
  import akka.typed._
  import akka.typed.scaladsl.Actor
  import scala.concurrent._
  import scala.concurrent.duration._
  import akka.util.Timeout
  implicit val timeout = Timeout(5.seconds)
"""
