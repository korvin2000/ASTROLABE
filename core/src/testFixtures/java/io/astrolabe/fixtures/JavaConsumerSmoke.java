package io.astrolabe.fixtures;

import io.astrolabe.Astrolabe;

/** Proves the Java source set of the test fixtures compiles against core (TODO P0.1.1 Done criterion). */
public final class JavaConsumerSmoke {
    private JavaConsumerSmoke() {
    }

    public static String describe() {
        return Astrolabe.MODULE + " " + Astrolabe.VERSION;
    }
}
