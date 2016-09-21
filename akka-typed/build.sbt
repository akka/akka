import akka._

AkkaBuild.defaultSettings
AkkaBuild.experimentalSettings
Formatting.formatSettings

Dependencies.typed

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
