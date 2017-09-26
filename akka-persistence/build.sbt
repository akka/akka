import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, Protobuf }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.persistence
Dependencies.persistence
Protobuf.settings

fork in Test := true
