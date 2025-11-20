package com.cajunsystems.persistence;

import com.cajunsystems.ActorSystem;

/**
 * Helper class for adding persistence capabilities to an ActorSystem.
 * This class provides static methods to create and retrieve persistence extensions for actor systems.
 */
public class ActorSystemPersistenceHelper {
    
    /**
     * Gets a persistence extension for the specified actor system.
     * This method allows for fluent configuration of persistence providers.
     * 
     * @param actorSystem The actor system to extend
     * @return A new ActorSystemPersistenceExtension for the actor system
     */
    public static ActorSystemPersistenceExtension persistence(ActorSystem actorSystem) {
        return new ActorSystemPersistenceExtension(actorSystem);
    }
    
    /**
     * Sets a custom persistence provider for the specified actor system.
     * This is a convenience method for setting the default persistence provider.
     * 
     * @param actorSystem The actor system to configure
     * @param provider The persistence provider to use
     */
    public static void setPersistenceProvider(ActorSystem actorSystem, PersistenceProvider provider) {
        persistence(actorSystem).withPersistenceProvider(provider);
    }
    
    /**
     * Sets a named persistence provider for the specified actor system.
     * This is a convenience method for setting the default persistence provider by name.
     * 
     * @param actorSystem The actor system to configure
     * @param providerName The name of the persistence provider to use
     * @throws IllegalArgumentException if the provider is not found
     */
    public static void setPersistenceProvider(ActorSystem actorSystem, String providerName) {
        persistence(actorSystem).withPersistenceProvider(providerName);
    }
    
    /**
     * Gets the current default persistence provider for the specified actor system.
     * 
     * @param actorSystem The actor system to get the provider for
     * @return The default persistence provider
     */
    public static PersistenceProvider getPersistenceProvider(ActorSystem actorSystem) {
        return persistence(actorSystem).getPersistenceProvider();
    }
}
