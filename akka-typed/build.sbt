import akka.{ AkkaBuild, Dependencies, Formatting }

Dependencies.typed

AkkaBuild.defaultSettings
AkkaBuild.experimentalSettings
Formatting.formatSettings

disablePlugins(MimaPlugin)

initialCommands := """
  import akka.typed._
  import ScalaDSL._
  import scala.concurrent._
  import duration._
  import akka.util.Timeout
  implicit val timeout = Timeout(5.seconds)
"""
