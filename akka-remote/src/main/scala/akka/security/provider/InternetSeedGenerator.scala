// ============================================================================
//   Copyright 2006-2010 Daniel W. Dyer
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
// ============================================================================
package akka.security.provider

import org.uncommons.maths.random.{ SeedGenerator, SeedException, SecureRandomSeedGenerator, RandomDotOrgSeedGenerator, DevRandomSeedGenerator }

/**
 * Internal API
 * Seed generator that maintains multiple strategies for seed
 * generation and will delegate to the best one available for the
 * current operating environment.
 * @author Daniel Dyer
 */
object InternetSeedGenerator {
  /**
   * @return The singleton instance of this class.
   */
  def getInstance: InternetSeedGenerator = {
    INSTANCE
  }

  /**Singleton instance. */
  private final val INSTANCE: InternetSeedGenerator = new InternetSeedGenerator
  /**Delegate generators. */
  private final val GENERATORS: Seq[SeedGenerator] =
    new RandomDotOrgSeedGenerator :: // first try the Internet seed generator
      new SecureRandomSeedGenerator :: // this is last because it always works
      Nil
}

final class InternetSeedGenerator extends SeedGenerator {
  /**
   * Generates a seed by trying each of the available strategies in
   * turn until one succeeds.  Tries the most suitable strategy first
   * and eventually degrades to the least suitable (but guaranteed to
   * work) strategy.
   * @param length The length (in bytes) of the seed.
   * @return A random seed of the requested length.
   */
  def generateSeed(length: Int): Array[Byte] = {
    for (generator ← InternetSeedGenerator.GENERATORS) {
      try {
        return generator.generateSeed(length)
      } catch {
        case ex: SeedException ⇒ // Ignore and try the next generator...
      }
    }
    // This shouldn't happen as at least one of the generators should be
    // able to generate a seed.
    throw new IllegalStateException("All available seed generation strategies failed.")
  }
}

