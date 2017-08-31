//import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, ScaladocNoVerificationOfDiagrams } // FIXME
import akka.{ AkkaBuild, Dependencies, Formatting, OSGi } 

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.agent
Dependencies.agent

//enablePlugins(ScaladocNoVerificationOfDiagrams) // FIXME
