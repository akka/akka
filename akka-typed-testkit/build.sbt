import akka.{ AkkaBuild, Formatting, OSGi }

AkkaBuild.defaultSettings
Formatting.formatSettings

disablePlugins(MimaPlugin)
