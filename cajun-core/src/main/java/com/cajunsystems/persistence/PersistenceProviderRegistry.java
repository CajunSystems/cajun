package com.cajunsystems.persistence;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for persistence providers.
 * This class manages the available persistence providers and provides a default provider.
 * <p>
 * Providers are discovered automatically via {@link ServiceLoader}: any jar on the classpath
 * that declares a {@code META-INF/services/com.cajunsystems.persistence.PersistenceProvider}
 * entry is registered when the registry is first used. The {@code cajun-persistence} module
 * registers the default "filesystem" provider this way. Additional providers can be
 * registered programmatically via {@link #registerProvider(PersistenceProvider)}.
 */
public class PersistenceProviderRegistry {

    // Singleton instance
    private static PersistenceProviderRegistry instance;

    // Map of provider name to provider instance
    private final Map<String, PersistenceProvider> providers = new ConcurrentHashMap<>();

    // The default provider name
    private String defaultProviderName = "filesystem";

    /**
     * Private constructor to enforce singleton pattern.
     * Discovers and registers providers available on the classpath.
     */
    private PersistenceProviderRegistry() {
        ServiceLoader.load(PersistenceProvider.class).forEach(this::registerProvider);
    }
    
    /**
     * Gets the singleton instance of the registry.
     *
     * @return The singleton instance
     */
    public static synchronized PersistenceProviderRegistry getInstance() {
        if (instance == null) {
            instance = new PersistenceProviderRegistry();
        }
        return instance;
    }
    
    /**
     * Registers a persistence provider.
     *
     * @param provider The provider to register
     */
    public void registerProvider(PersistenceProvider provider) {
        providers.put(provider.getProviderName(), provider);
    }
    
    /**
     * Unregisters a persistence provider.
     *
     * @param providerName The name of the provider to unregister
     */
    public void unregisterProvider(String providerName) {
        if (!providerName.equals(defaultProviderName)) {
            providers.remove(providerName);
        } else {
            throw new IllegalArgumentException("Cannot unregister the default provider");
        }
    }
    
    /**
     * Gets a persistence provider by name.
     *
     * @param providerName The name of the provider to get
     * @return The provider instance
     * @throws IllegalArgumentException if the provider is not found
     */
    public PersistenceProvider getProvider(String providerName) {
        PersistenceProvider provider = providers.get(providerName);
        if (provider == null) {
            throw new IllegalArgumentException("Persistence provider not found: " + providerName);
        }
        return provider;
    }
    
    /**
     * Gets the default persistence provider.
     *
     * @return The default provider instance
     */
    public PersistenceProvider getDefaultProvider() {
        return getProvider(defaultProviderName);
    }
    
    /**
     * Sets the default persistence provider.
     *
     * @param providerName The name of the provider to set as default
     * @throws IllegalArgumentException if the provider is not found
     */
    public void setDefaultProvider(String providerName) {
        if (!providers.containsKey(providerName)) {
            throw new IllegalArgumentException("Persistence provider not found: " + providerName);
        }
        this.defaultProviderName = providerName;
    }
    
    /**
     * Gets all registered providers.
     *
     * @return A map of provider name to provider instance
     */
    public Map<String, PersistenceProvider> getAllProviders() {
        return Map.copyOf(providers);
    }
}
