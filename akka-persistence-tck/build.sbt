import akka.{ AkkaBuild, Dependencies, Formatting, OSGi }

AkkaBuild.defaultSettings
Formatting.formatSettings
// OSGi.persistenceTck TODO: we do need to export this as OSGi bundle too?
Dependencies.persistenceTck

fork in Test := true

disablePlugins(MimaPlugin)
