/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.security.provider;

import org.uncommons.maths.random.SecureRandomSeedGenerator;
import org.uncommons.maths.random.SeedException;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Internal API
 */
public class AES128CounterRNGFast extends java.security.SecureRandomSpi {
    private org.uncommons.maths.random.AESCounterRNG rng;

    public AES128CounterRNGFast() throws SeedException, GeneralSecurityException {
       rng = new org.uncommons.maths.random.AESCounterRNG(new SecureRandomSeedGenerator());
    }

    /**
     * This is managed internally only
     */
    @Override
    protected void engineSetSeed(byte[] seed) {

    }

    /**
     * Generates a user-specified number of random bytes.
     *
     * @param bytes the array to be filled in with random bytes.
     */
    @Override
    protected void engineNextBytes(byte[] bytes) {
        rng.nextBytes(bytes);
    }

    /**
     * Returns the given number of seed bytes.  This call may be used to
     * seed other random number generators.
     *
     * @param numBytes the number of seed bytes to generate.
     * @return the seed bytes.
     */
    @Override
    protected byte[] engineGenerateSeed(int numBytes) {
        return (new SecureRandom()).generateSeed(numBytes);
    }
}
