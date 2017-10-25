import akka.{ AkkaBuild, Formatting, OSGi }

AkkaBuild.defaultSettings
Formatting.formatSettings

enablePlugins(akka.Unidoc)
disablePlugins(MimaPlugin)
