/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.security.provider;

import org.uncommons.maths.random.DefaultSeedGenerator;

import java.security.GeneralSecurityException;

/**
 * Internal API
 */
public class AES128CounterRNGSecure extends java.security.SecureRandomSpi {
    private org.uncommons.maths.random.AESCounterRNG rng;

    public AES128CounterRNGSecure() throws GeneralSecurityException {
        rng = new org.uncommons.maths.random.AESCounterRNG();
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
        return DefaultSeedGenerator.getInstance().generateSeed(numBytes);
    }
}
