import akka.{ AkkaBuild, Formatting }

AkkaBuild.defaultSettings
AkkaBuild.experimentalSettings
Formatting.formatSettings

disablePlugins(MimaPlugin)
