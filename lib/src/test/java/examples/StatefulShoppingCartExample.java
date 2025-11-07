///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS com.cajunsystems:cajun:0.1.4
//JAVA 21+
//PREVIEW

package examples;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;
import com.cajunsystems.StatefulActor;
import com.cajunsystems.SupervisionStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example demonstrating the use of stateful actors in a shopping cart scenario.
 * 
 * This example shows a product catalog system with:
 * 1. Product actors that maintain a set of variant products
 * 2. Variant actors that manage stock items
 * 3. Stock actors that can be owned by users
 * 
 * The example demonstrates:
 * - Hierarchical actor relationships
 * - State persistence and recovery
 * - Message passing between actors
 * - Actor supervision
 */
public class StatefulShoppingCartExample {
    private static final Logger logger = LoggerFactory.getLogger(StatefulShoppingCartExample.class);

    public static void main(String[] args) throws Exception {
        // Create an actor system
        ActorSystem system = new ActorSystem();
        
        try {
            // Create the product catalog and wait for it to complete
            createProductCatalog(system);
            
            // Simulate user shopping and wait for it to complete
            simulateUserShopping(system);
            
            // Add a small delay to ensure all operations have completed
            logger.info("Waiting for all operations to complete");
            Thread.sleep(500);
            
        } catch (Exception e) {
            logger.error("Error in shopping cart example", e);
            throw e;
        } finally {
            // Shutdown the actor system - this will now properly wait for all StatefulActor
            // persistence operations to complete before shutting down
            logger.info("Shutting down actor system");
            system.shutdown();
        }
    }

    /**
     * Creates a product catalog with products, variants, and stock items.
     */
    private static void createProductCatalog(ActorSystem system) throws Exception {
        logger.info("Creating product catalog");
        
        // Create product actors
        ProductActor tShirtProduct = new ProductActor(system, "product-tshirt", 
                new ProductState("T-Shirt", "A comfortable cotton t-shirt"));
        tShirtProduct.start();
        tShirtProduct.forceInitializeState().join();
        
        ProductActor jeansProduct = new ProductActor(system, "product-jeans", 
                new ProductState("Jeans", "Classic blue jeans"));
        jeansProduct.start();
        jeansProduct.forceInitializeState().join();
        
        // Add variants to the t-shirt product
        tShirtProduct.tell(new ProductMessage.AddVariant("small-red", "Small Red", 19.99));
        tShirtProduct.tell(new ProductMessage.AddVariant("medium-red", "Medium Red", 19.99));
        tShirtProduct.tell(new ProductMessage.AddVariant("large-red", "Large Red", 24.99));
        tShirtProduct.tell(new ProductMessage.AddVariant("small-blue", "Small Blue", 19.99));
        tShirtProduct.tell(new ProductMessage.AddVariant("medium-blue", "Medium Blue", 19.99));
        tShirtProduct.tell(new ProductMessage.AddVariant("large-blue", "Large Blue", 24.99));
        
        // Add variants to the jeans product
        jeansProduct.tell(new ProductMessage.AddVariant("30-32", "30x32", 49.99));
        jeansProduct.tell(new ProductMessage.AddVariant("32-32", "32x32", 49.99));
        jeansProduct.tell(new ProductMessage.AddVariant("34-32", "34x32", 54.99));
        jeansProduct.tell(new ProductMessage.AddVariant("36-32", "36x32", 54.99));
        
        // Wait for variants to be created
        Thread.sleep(500);
        
        // Add stock to each variant
        for (String productId : new String[]{"product-tshirt", "product-jeans"}) {
            CountDownLatch variantsLatch = new CountDownLatch(1);
            List<String> variants = new ArrayList<>();
            
            ProductActor productActor = (ProductActor) system.getActor(new Pid(productId, system));
            if (productActor != null) {
                productActor.tell(new ProductMessage.GetVariants(variantList -> {
                    variants.addAll(variantList);
                    variantsLatch.countDown();
                }));
            } else {
                // Actor not found, just count down the latch to avoid blocking
                logger.warn("Product actor {} not found", productId);
                variantsLatch.countDown();
            }
            
            variantsLatch.await(1, TimeUnit.SECONDS);
            
            for (String variantId : variants) {
                // Add 10 stock items to each variant
                for (int i = 0; i < 10; i++) {
                    VariantActor variantActor = (VariantActor) system.getActor(new Pid(variantId, system));
                    if (variantActor != null) {
                        variantActor.tell(new VariantMessage.AddStockItem());
                    } else {
                        logger.warn("Variant actor {} not found", variantId);
                        break; // Skip to next variant if this one is not found
                    }
                }
            }
        }
        
        logger.info("Product catalog created successfully");
    }

    /**
     * Simulates a user shopping experience.
     */
    private static void simulateUserShopping(ActorSystem system) throws Exception {
        logger.info("Simulating user shopping");
        
        // User information
        String userId = "user123";
        
        // Get all variants for t-shirts
        CountDownLatch tshirtVariantsLatch = new CountDownLatch(1);
        List<String> tshirtVariants = new ArrayList<>();
        
        ProductActor tshirtProduct = (ProductActor) system.getActor(new Pid("product-tshirt", system));
        if (tshirtProduct != null) {
            tshirtProduct.tell(new ProductMessage.GetVariants(variantList -> {
                tshirtVariants.addAll(variantList);
                tshirtVariantsLatch.countDown();
            }));
        } else {
            // Actor not found, just count down the latch to avoid blocking
            logger.warn("T-shirt product actor not found");
            tshirtVariantsLatch.countDown();
        }
        
        tshirtVariantsLatch.await(1, TimeUnit.SECONDS);
        
        // Select a variant and add it to the cart
        if (!tshirtVariants.isEmpty()) {
            String selectedVariant = tshirtVariants.get(0); // Select the first variant
            
            // Get available stock for the selected variant
            CountDownLatch stockLatch = new CountDownLatch(1);
            List<String> availableStock = new ArrayList<>();
            
            VariantActor variantActor = (VariantActor) system.getActor(new Pid(selectedVariant, system));
            if (variantActor != null) {
                variantActor.tell(new VariantMessage.GetAvailableStock(stockList -> {
                    availableStock.addAll(stockList);
                    stockLatch.countDown();
                }));
            } else {
                // Actor not found, just count down the latch to avoid blocking
                logger.warn("Variant actor {} not found", selectedVariant);
                stockLatch.countDown();
            }
            
            stockLatch.await(1, TimeUnit.SECONDS);
            
            // Add the first available stock item to the user's cart
            if (!availableStock.isEmpty()) {
                String stockId = availableStock.get(0);
                logger.info("User {} is adding stock item {} to cart", userId, stockId);
                
                StockActor stockActor = (StockActor) system.getActor(new Pid(stockId, system));
                if (stockActor != null) {
                    stockActor.tell(new StockMessage.AssignToUser(userId));
                } else {
                    logger.warn("Stock actor {} not found", stockId);
                }
                
                // Wait for the stock to be assigned
                Thread.sleep(100);
                
                // Verify the stock is assigned to the user, but only if we found the actor
                if (stockActor != null) {
                    CountDownLatch ownerLatch = new CountDownLatch(1);
                    String[] owner = new String[1];
                    
                    stockActor.tell(new StockMessage.GetOwner(currentOwner -> {
                        owner[0] = currentOwner;
                        ownerLatch.countDown();
                    }));
                    
                    ownerLatch.await(1, TimeUnit.SECONDS);
                    
                    if (userId.equals(owner[0])) {
                        logger.info("Stock item {} successfully assigned to user {}", stockId, userId);
                    } else {
                        logger.error("Failed to assign stock item to user");
                    }
                    
                    // Now let's release the stock (simulating removing from cart)
                    logger.info("User {} is removing stock item {} from cart", userId, stockId);
                    stockActor.tell(new StockMessage.ReleaseFromUser());
                }
                
                // Wait for the stock to be released
                Thread.sleep(100);
                
                // Verify the stock is released, but only if we found the actor
                if (stockActor != null) {
                    CountDownLatch releaseLatch = new CountDownLatch(1);
                    String[] newOwner = new String[1];
                    
                    stockActor.tell(new StockMessage.GetOwner(currentOwner -> {
                        newOwner[0] = currentOwner;
                        releaseLatch.countDown();
                    }));
                    
                    releaseLatch.await(1, TimeUnit.SECONDS);
                    
                    if (newOwner[0] == null) {
                        logger.info("Stock item {} successfully released from user {}", stockId, userId);
                    } else {
                        logger.error("Failed to release stock item from user");
                    }
                }
            } else {
                logger.info("No available stock for variant {}", selectedVariant);
            }
        } else {
            logger.info("No variants found for t-shirt product");
        }
        
        logger.info("Shopping simulation completed");
    }

    /**
     * State for a product actor.
     */
    public static class ProductState implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private final String name;
        private final String description;
        private final Map<String, String> variants = new HashMap<>(); // variantId -> variantActorId
        
        public ProductState(String name, String description) {
            this.name = name;
            this.description = description;
        }
        
        public String getName() {
            return name;
        }
        
        public String getDescription() {
            return description;
        }
        
        public Map<String, String> getVariants() {
            return variants;
        }
        
        public void addVariant(String variantId, String variantActorId) {
            variants.put(variantId, variantActorId);
        }
    }
    
    /**
     * Messages for the product actor.
     */
    public sealed interface ProductMessage extends Serializable permits 
            ProductMessage.AddVariant,
            ProductMessage.GetVariants {
        
        /**
         * Message to add a variant to a product.
         */
        record AddVariant(String variantId, String name, double price) implements ProductMessage {
            private static final long serialVersionUID = 1L;
        }
        
        /**
         * Message to get all variants of a product.
         */
        static final class GetVariants implements ProductMessage {
            private static final long serialVersionUID = 1L;
            
            private transient java.util.function.Consumer<List<String>> callback;
            
            public GetVariants(java.util.function.Consumer<List<String>> callback) {
                this.callback = callback;
            }
            
            public java.util.function.Consumer<List<String>> callback() {
                return callback;
            }
            
            private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
                in.defaultReadObject();
                if (callback == null) {
                    callback = (list) -> {};
                }
            }
        }
    }
    
    /**
     * Actor that manages a product and its variants.
     */
    public static class ProductActor extends StatefulActor<ProductState, ProductMessage> {

        public ProductActor(ActorSystem system, String actorId, ProductState initialState) {
            super(system, actorId, initialState);
            withSupervisionStrategy(SupervisionStrategy.RESTART);
        }

        @Override
        protected ProductState processMessage(ProductState state, ProductMessage message) {
            if (message instanceof ProductMessage.AddVariant addVariant) {
                // Create a new variant actor
                String variantActorId = getActorId() + "-variant-" + addVariant.variantId();
                VariantActor variantActor = new VariantActor(self().system(), variantActorId,
                        new VariantState(addVariant.variantId(), addVariant.name(), addVariant.price()));
                variantActor.start();

                // Add the variant to the product state
                state.addVariant(addVariant.variantId(), variantActorId);
                logger.info("Added variant {} to product {}", addVariant.variantId(), getActorId());

                return state;
            } else if (message instanceof ProductMessage.GetVariants getVariants) {
                // Return all variant actor IDs
                getVariants.callback().accept(new ArrayList<>(state.getVariants().values()));
                return state;
            }

            return state;
        }
    }

    /**
     * State for a variant actor.
     */
    public static class VariantState implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private final String variantId;
        private final String name;
        private final double price;
        private final List<String> stockItems = new ArrayList<>(); // stockActorIds
        
        public VariantState(String variantId, String name, double price) {
            this.variantId = variantId;
            this.name = name;
            this.price = price;
        }
        
        public String getVariantId() {
            return variantId;
        }
        
        public String getName() {
            return name;
        }
        
        public double getPrice() {
            return price;
        }
        
        public List<String> getStockItems() {
            return stockItems;
        }
        
        public void addStockItem(String stockActorId) {
            stockItems.add(stockActorId);
        }
    }
    
    /**
     * Messages for the variant actor.
     */
    public sealed interface VariantMessage extends Serializable permits 
            VariantMessage.AddStockItem,
            VariantMessage.GetAvailableStock {
        
        /**
         * Message to add a stock item to a variant.
         */
        record AddStockItem() implements VariantMessage {
            private static final long serialVersionUID = 1L;
        }
        
        /**
         * Message to get available (unowned) stock items.
         */
        static final class GetAvailableStock implements VariantMessage {
            private static final long serialVersionUID = 1L;
            
            private transient java.util.function.Consumer<List<String>> callback;
            
            public GetAvailableStock(java.util.function.Consumer<List<String>> callback) {
                this.callback = callback;
            }
            
            public java.util.function.Consumer<List<String>> callback() {
                return callback;
            }
            
            private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
                in.defaultReadObject();
                if (callback == null) {
                    callback = (list) -> {};
                }
            }
        }
    }

    /**
     * Actor that manages a product variant and its stock.
     */
    public static class VariantActor extends StatefulActor<VariantState, VariantMessage> {

        public VariantActor(ActorSystem system, String actorId, VariantState initialState) {
            super(system, actorId, initialState);
            withSupervisionStrategy(SupervisionStrategy.RESTART);
        }

        @Override
        protected VariantState processMessage(VariantState state, VariantMessage message) {
            if (message instanceof VariantMessage.AddStockItem) {
                // Create a new stock actor
                String stockId = getActorId() + "-stock-" + UUID.randomUUID().toString();
                StockActor stockActor = new StockActor(self().system(), stockId,
                        new StockState(state.getVariantId()));
                stockActor.start();

                // Add the stock to the variant state
                state.addStockItem(stockId);
                logger.debug("Added stock item {} to variant {}", stockId, getActorId());

                return state;
            } else if (message instanceof VariantMessage.GetAvailableStock getStock) {
                // Query each stock actor to check if it's available
                List<String> stockItems = state.getStockItems();
                List<String> availableStock = new ArrayList<>();
                CountDownLatch latch = new CountDownLatch(stockItems.size());

                for (String stockId : stockItems) {
                    StockActor stockActor = (StockActor) self().system().getActor(new Pid(stockId, self().system()));
                    stockActor.tell(new StockMessage.GetOwner(owner -> {
                        if (owner == null) {
                            availableStock.add(stockId);
                        }
                        latch.countDown();
                    }));
                }

                
                try {
                    latch.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                getStock.callback().accept(availableStock);
                return state;
            }
            
            return state;
        }
    }
    
    /**
     * State for a stock actor.
     */
    public static class StockState implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private final String variantId;
        private String ownerId; // User who owns this stock item (null if available)
        
        public StockState(String variantId) {
            this.variantId = variantId;
        }
        
        public String getVariantId() {
            return variantId;
        }
        
        public String getOwnerId() {
            return ownerId;
        }
        
        public void setOwnerId(String ownerId) {
            this.ownerId = ownerId;
        }
    }
    
    /**
     * Messages for the stock actor.
     */
    public sealed interface StockMessage extends Serializable permits 
            StockMessage.AssignToUser,
            StockMessage.ReleaseFromUser,
            StockMessage.GetOwner {
        
        /**
         * Message to assign a stock item to a user.
         */
        record AssignToUser(String userId) implements StockMessage {
            private static final long serialVersionUID = 1L;
        }
        
        /**
         * Message to release a stock item from a user.
         */
        record ReleaseFromUser() implements StockMessage {
            private static final long serialVersionUID = 1L;
        }
        
        /**
         * Message to get the current owner of a stock item.
         */
        static final class GetOwner implements StockMessage {
            private static final long serialVersionUID = 1L;
            
            private transient java.util.function.Consumer<String> callback;
            
            public GetOwner(java.util.function.Consumer<String> callback) {
                this.callback = callback;
            }
            
            public java.util.function.Consumer<String> callback() {
                return callback;
            }
            
            private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
                in.defaultReadObject();
                if (callback == null) {
                    callback = (owner) -> {};
                }
            }
        }
    }
    
    /**
     * Actor that manages a stock item.
     */
    public static class StockActor extends StatefulActor<StockState, StockMessage> {
        
        public StockActor(ActorSystem system, String actorId, StockState initialState) {
            super(system, actorId, initialState);
            withSupervisionStrategy(SupervisionStrategy.RESTART);
        }
        
        @Override
        protected StockState processMessage(StockState state, StockMessage message) {
            if (message instanceof StockMessage.AssignToUser assignToUser) {
                if (state.getOwnerId() == null) {
                    state.setOwnerId(assignToUser.userId());
                    logger.debug("Stock item {} assigned to user {}", getActorId(), assignToUser.userId());
                } else {
                    logger.warn("Stock item {} already assigned to user {}", getActorId(), state.getOwnerId());
                }
                return state;
            } else if (message instanceof StockMessage.ReleaseFromUser) {
                String previousOwner = state.getOwnerId();
                state.setOwnerId(null);
                if (previousOwner != null) {
                    logger.debug("Stock item {} released from user {}", getActorId(), previousOwner);
                }
                return state;
            } else if (message instanceof StockMessage.GetOwner getOwner) {
                getOwner.callback().accept(state.getOwnerId());
                return state;
            }
            
            return state;
        }
    }
}
