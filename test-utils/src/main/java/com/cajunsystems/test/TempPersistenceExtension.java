package com.cajunsystems.test;

import com.cajunsystems.persistence.PersistenceProviderRegistry;
import com.cajunsystems.persistence.impl.FileSystemPersistenceProvider;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * JUnit 5 extension that configures persistence to use a temporary directory
 * that is cleaned up after tests complete.
 *
 * Usage:
 * <pre>
 * {@code
 * @ExtendWith(TempPersistenceExtension.class)
 * class MyTest {
 *     // Tests will use temp directory for persistence
 * }
 * }
 * </pre>
 */
public class TempPersistenceExtension implements BeforeAllCallback, AfterAllCallback {

    private static final String TEMP_DIR_KEY = "cajun.test.tempDir";
    private Path tempDir;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        // Create a temporary directory for this test class
        String testClassName = context.getRequiredTestClass().getSimpleName();
        tempDir = Files.createTempDirectory("cajun-test-" + testClassName + "-");

        // Store in extension context for potential access by tests
        context.getStore(ExtensionContext.Namespace.GLOBAL).put(TEMP_DIR_KEY, tempDir);

        // Register a filesystem provider pointing to the temp directory
        FileSystemPersistenceProvider tempProvider = new FileSystemPersistenceProvider(tempDir.toString());
        PersistenceProviderRegistry registry = PersistenceProviderRegistry.getInstance();

        // Register and set as default
        registry.registerProvider(tempProvider);
        registry.setDefaultProvider(tempProvider.getProviderName());
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        try {
            // Restore filesystem provider to use default directory
            PersistenceProviderRegistry registry = PersistenceProviderRegistry.getInstance();
            FileSystemPersistenceProvider defaultProvider = new FileSystemPersistenceProvider();
            registry.registerProvider(defaultProvider);
            registry.setDefaultProvider(defaultProvider.getProviderName());
        } finally {
            // Clean up the temporary directory
            if (tempDir != null && Files.exists(tempDir)) {
                deleteDirectory(tempDir);
            }
        }
    }

    /**
     * Recursively delete a directory and all its contents.
     */
    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Get the temporary directory being used for persistence in this test.
     * Can be called from test methods if needed.
     */
    public static Path getTempDir(ExtensionContext context) {
        return (Path) context.getStore(ExtensionContext.Namespace.GLOBAL).get(TEMP_DIR_KEY);
    }
}
