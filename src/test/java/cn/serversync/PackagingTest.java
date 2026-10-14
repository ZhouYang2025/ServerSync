package cn.serversync;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackagingTest {
    @Test void mixinPackageContainsOnlyMixinClasses() throws Exception {
        try (var jar = new ZipFile(Path.of(System.getProperty("serversync.test.jar")).toFile())) {
            String config;
            try (var in = jar.getInputStream(jar.getEntry("serversync.mixins.json"))) { config = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            assertTrue(config.contains("\"package\":\"cn.serversync.mixin\""));
            assertNotNull(jar.getEntry("cn/serversync/ServerSync.class"));
            assertNotNull(jar.getEntry("cn/serversync/mixin/ConnectionMixin.class"));
            assertNotNull(jar.getEntry("cn/serversync/mixin/ConnectMixin.class"));
            var allowed = java.util.Set.of("cn/serversync/mixin/ConnectMixin.class", "cn/serversync/mixin/ConnectionMixin.class");
            for (var entry : java.util.Collections.list(jar.entries())) {
                if (entry.getName().startsWith("cn/serversync/mixin/") && entry.getName().endsWith(".class")) {
                    assertTrue(allowed.contains(entry.getName()), "Ordinary class in protected mixin package: " + entry.getName());
                }
            }
            assertNull(jar.getEntry("cn/serversync/ConnectionMixin.class"));
            assertNull(jar.getEntry("cn/serversync/ConnectMixin.class"));
        }
    }
}
