//import akka.{ AkkaBuild, Dependencies, Formatting, ScaladocNoVerificationOfDiagrams, OSGi }
import akka.{ AkkaBuild, Dependencies, Formatting, OSGi } // FIXME

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.persistenceQuery
Dependencies.persistenceQuery

fork in Test := true

//enablePlugins(ScaladocNoVerificationOfDiagrams) // FIXME
