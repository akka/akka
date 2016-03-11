import akka.{ AkkaBuild, Dependencies, Formatting, OSGi }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.persistence
Dependencies.persistence

fork in Test := true
