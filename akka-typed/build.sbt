import akka.{ AkkaBuild, Formatting }

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

cancelable in Global := true
