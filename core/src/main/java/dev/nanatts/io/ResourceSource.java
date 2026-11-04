package dev.nanatts.io;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Abstraction over where binary resources (voice packs, dictionaries) come from.
 * On Android, implement this with {@code AssetManager::open}.
 */
@FunctionalInterface
public interface ResourceSource {

    InputStream open(String name) throws IOException;

    /** Resources resolved relative to the root of the given class loader. */
    static ResourceSource classpath(ClassLoader loader) {
        return name -> {
            InputStream in = loader.getResourceAsStream(name);
            if (in == null) {
                throw new FileNotFoundException("classpath resource not found: " + name);
            }
            return in;
        };
    }

    static ResourceSource classpath() {
        return classpath(ResourceSource.class.getClassLoader());
    }

    /** Resources resolved relative to a directory on the file system. */
    static ResourceSource directory(File dir) {
        return name -> new FileInputStream(new File(dir, name));
    }
}
