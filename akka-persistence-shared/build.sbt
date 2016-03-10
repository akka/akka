import akka._

AkkaBuild.defaultSettings
AkkaBuild.dontPublishSettings
Formatting.formatSettings
Dependencies.persistenceShared

fork in Test := true

disablePlugins(MimaPlugin)
