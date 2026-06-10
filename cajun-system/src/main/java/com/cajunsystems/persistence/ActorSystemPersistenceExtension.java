package com.cajunsystems.persistence;

import com.cajunsystems.ActorSystem;

/**
 * Extension class for the ActorSystem to support persistence provider configuration.
 * This class provides helper methods to configure persistence in the actor system.
 */
public class ActorSystemPersistenceExtension {
    
    // No need to store the actor system as we're only configuring the global registry
    // This class is designed for fluent API use only
    
    /**
     * Creates a new ActorSystemPersistenceExtension for the specified actor system.
     * 
     * @param actorSystem The actor system to extend
     */
    public ActorSystemPersistenceExtension(ActorSystem actorSystem) {
        // We don't need to store the actor system as we only configure the global registry
    }
    
    /**
     * Sets the persistence provider to use for this actor system.
     * This will only affect actors created after this call.
     * 
     * @param provider The persistence provider to use
     * @return This extension instance for method chaining
     */
    public ActorSystemPersistenceExtension withPersistenceProvider(PersistenceProvider provider) {
        PersistenceProviderRegistry.getInstance().registerProvider(provider);
        PersistenceProviderRegistry.getInstance().setDefaultProvider(provider.getProviderName());
        return this;
    }
    
    /**
     * Sets a named persistence provider to use for this actor system.
     * The provider must already be registered in the PersistenceProviderRegistry.
     * This will only affect actors created after this call.
     * 
     * @param providerName The name of the persistence provider to use
     * @return This extension instance for method chaining
     * @throws IllegalArgumentException if the provider is not found
     */
    public ActorSystemPersistenceExtension withPersistenceProvider(String providerName) {
        PersistenceProviderRegistry.getInstance().setDefaultProvider(providerName);
        return this;
    }
    
    /**
     * Gets the current default persistence provider for this actor system.
     * 
     * @return The default persistence provider
     */
    public PersistenceProvider getPersistenceProvider() {
        return PersistenceProviderRegistry.getInstance().getDefaultProvider();
    }
    
    /**
     * Gets a named persistence provider from the registry.
     * 
     * @param providerName The name of the provider to get
     * @return The persistence provider
     * @throws IllegalArgumentException if the provider is not found
     */
    public PersistenceProvider getPersistenceProvider(String providerName) {
        return PersistenceProviderRegistry.getInstance().getProvider(providerName);
    }
}
