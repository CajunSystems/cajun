package com.cajunsystems.persistence;

import com.cajunsystems.ActorSystem;
import com.cajunsystems.Pid;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Utility class for rehydrating Pid references in state objects after deserialization.
 * 
 * When state is recovered from persistence, Pid objects are deserialized with null ActorSystem
 * references. This utility recursively traverses state objects and rehydrates all Pid references
 * with the correct ActorSystem.
 */
public class PidRehydrator {
    
    /**
     * Rehydrates all Pid references in the given state object with the specified ActorSystem.
     * This method recursively traverses the object graph and updates all Pid instances.
     *
     * @param state The state object to rehydrate
     * @param system The ActorSystem to associate with Pids
     * @param <S> The type of the state
     * @return The rehydrated state object
     */
    public static <S> S rehydrate(S state, ActorSystem system) {
        if (state == null || system == null) {
            return state;
        }
        
        return rehydrateRecursive(state, system, new IdentityHashMap<>());
    }
    
    @SuppressWarnings("unchecked")
    private static <S> S rehydrateRecursive(S obj, ActorSystem system, IdentityHashMap<Object, Object> visited) {
        if (obj == null) {
            return null;
        }
        
        // Avoid infinite loops with circular references
        if (visited.containsKey(obj)) {
            return (S) visited.get(obj);
        }
        
        // Handle Pid directly
        if (obj instanceof Pid pid) {
            Pid rehydrated = pid.system() == null ? pid.withSystem(system) : pid;
            visited.put(obj, rehydrated);
            return (S) rehydrated;
        }
        
        // Handle primitives and immutable types
        if (isPrimitiveOrImmutable(obj)) {
            return obj;
        }
        
        // For records, we need to create a new instance with rehydrated fields
        if (obj.getClass().isRecord()) {
            return rehydrateRecord(obj, system, visited);
        }
        
        // For collections
        if (obj instanceof Collection) {
            return (S) rehydrateCollection((Collection<?>) obj, system, visited);
        }
        
        if (obj instanceof Map) {
            return (S) rehydrateMap((Map<?, ?>) obj, system, visited);
        }
        
        // For regular objects, try to rehydrate fields in place
        visited.put(obj, obj);
        rehydrateFields(obj, system, visited);
        return obj;
    }
    
    @SuppressWarnings("unchecked")
    private static <S> S rehydrateRecord(S record, ActorSystem system, IdentityHashMap<Object, Object> visited) {
        try {
            Class<?> recordClass = record.getClass();
            var components = recordClass.getRecordComponents();
            Object[] args = new Object[components.length];
            
            for (int i = 0; i < components.length; i++) {
                var component = components[i];
                Object value = component.getAccessor().invoke(record);
                args[i] = rehydrateRecursive(value, system, visited);
            }
            
            // Find the canonical constructor
            Class<?>[] paramTypes = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                paramTypes[i] = components[i].getType();
            }
            var constructor = recordClass.getDeclaredConstructor(paramTypes);
            constructor.setAccessible(true);
            
            S newRecord = (S) constructor.newInstance(args);
            visited.put(record, newRecord);
            return newRecord;
        } catch (Exception e) {
            // If we can't reconstruct the record, return it as-is
            return record;
        }
    }
    
    private static Collection<?> rehydrateCollection(Collection<?> collection, ActorSystem system, 
                                                     IdentityHashMap<Object, Object> visited) {
        try {
            Collection<Object> newCollection = collection.getClass().getDeclaredConstructor().newInstance();
            visited.put(collection, newCollection);
            
            for (Object item : collection) {
                newCollection.add(rehydrateRecursive(item, system, visited));
            }
            return newCollection;
        } catch (Exception e) {
            // If we can't create a new collection, return the original
            return collection;
        }
    }
    
    private static Map<?, ?> rehydrateMap(Map<?, ?> map, ActorSystem system, 
                                          IdentityHashMap<Object, Object> visited) {
        try {
            @SuppressWarnings("unchecked")
            Map<Object, Object> newMap = map.getClass().getDeclaredConstructor().newInstance();
            visited.put(map, newMap);
            
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = rehydrateRecursive(entry.getKey(), system, visited);
                Object value = rehydrateRecursive(entry.getValue(), system, visited);
                newMap.put(key, value);
            }
            return newMap;
        } catch (Exception e) {
            // If we can't create a new map, return the original
            return map;
        }
    }
    
    private static void rehydrateFields(Object obj, ActorSystem system, IdentityHashMap<Object, Object> visited) {
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                    continue;
                }
                
                try {
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    if (value != null) {
                        Object rehydrated = rehydrateRecursive(value, system, visited);
                        if (rehydrated != value) {
                            field.set(obj, rehydrated);
                        }
                    }
                } catch (Exception e) {
                    // Skip fields we can't access
                }
            }
            clazz = clazz.getSuperclass();
        }
    }
    
    private static boolean isPrimitiveOrImmutable(Object obj) {
        return obj instanceof String ||
               obj instanceof Number ||
               obj instanceof Boolean ||
               obj instanceof Character ||
               obj.getClass().isPrimitive();
    }
}
