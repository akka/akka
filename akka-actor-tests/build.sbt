import akka.{ AkkaBuild, Dependencies, Formatting }

AkkaBuild.defaultSettings
AkkaBuild.dontPublishSettings
Formatting.formatSettings
Dependencies.actorTests

disablePlugins(MimaPlugin)
