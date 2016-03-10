import akka.{ AkkaBuild, Dependencies, Formatting, ScaladocNoVerificationOfDiagrams, OSGi }

AkkaBuild.defaultSettings
AkkaBuild.experimentalSettings
Formatting.formatSettings
OSGi.persistenceQuery
Dependencies.persistenceQuery

fork in Test := true

enablePlugins(ScaladocNoVerificationOfDiagrams)
