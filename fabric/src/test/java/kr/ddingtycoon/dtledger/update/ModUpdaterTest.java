package kr.ddingtycoon.dtledger.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ModUpdaterTest {
    @TempDir Path dir;

    @Test void newerUsesNumericSemverAndRejectsMalformedValues() {
        assertTrue(ModUpdater.newer("0.1.10", "0.1.9"));
        assertFalse(ModUpdater.newer("v0.1.2", "0.1.1"));
        assertFalse(ModUpdater.newer("0.1", "0.0.1"));
        assertFalse(ModUpdater.newer("0.1.2", "0.1.2"));
    }

    @Test void checkCooldownIsRecordedBeforeNetworkWork() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ModUpdater updater = updater((uri, limit) -> {
            calls.incrementAndGet();
            throw new java.io.IOException("offline failure");
        }, ignored -> {});
        updater.check();
        awaitIdle(updater);
        var field = ModUpdater.class.getDeclaredField("lastCheckNanos"); field.setAccessible(true);
        long first = field.getLong(updater);
        updater.check();
        assertEquals(first, field.getLong(updater));
        assertEquals(1, calls.get());
        assertTrue(updater.status().startsWith("업데이트 확인 실패:"));
        assertNull(updater.offer());
    }

    @Test void staleOrDifferentApprovedOfferIsRejectedWithoutDownload() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> messages = new ArrayList<>();
        String release = "{\"draft\":false,\"prerelease\":false,\"tag_name\":\"v0.1.2\"," +
                "\"assets\":[{\"name\":\"billding-fabric-0.1.2.jar\",\"size\":3," +
                "\"digest\":\"sha256:" + "a".repeat(64) + "\"," +
                "\"browser_download_url\":\"https://github.com/kpar03004-droid/billding/releases/download/v0.1.2/billding-fabric-0.1.2.jar\"}]}";
        ModUpdater updater = updater((uri, limit) -> { calls.incrementAndGet(); return release.getBytes(StandardCharsets.UTF_8); }, messages::add);
        updater.check(); awaitIdle(updater);
        ModUpdater.Offer current = updater.offer();
        assertNotNull(current);
        var offerField = ModUpdater.class.getDeclaredField("offer"); offerField.setAccessible(true);
        offerField.set(updater, new ModUpdater.Offer("0.1.3", "billding-fabric-0.1.3.jar", 4, "b".repeat(64), current.url()));
        updater.download(current);
        updater.download(new ModUpdater.Offer("0.1.3", current.fileName(), current.size(), current.sha256(), current.url()));
        assertEquals(1, calls.get());
        assertFalse(updater.isBusy());
        assertFalse(updater.isQueued());
        assertEquals(2, messages.size());
    }

    @Test void boundedSubscriberCancelsWhenChunkExceedsLimit() throws Exception {
        var method = ModUpdater.class.getDeclaredMethod("boundedBody", long.class); method.setAccessible(true);
        @SuppressWarnings("unchecked") HttpResponse.BodyHandler<byte[]> handler =
                (HttpResponse.BodyHandler<byte[]>) method.invoke(null, 3L);
        HttpResponse.BodySubscriber<byte[]> subscriber = handler.apply(new HttpResponse.ResponseInfo() {
            public int statusCode() { return 200; }
            public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        });
        AtomicBoolean cancelled = new AtomicBoolean();
        subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            public void request(long n) {}
            public void cancel() { cancelled.set(true); }
        });
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2, 3, 4})));
        assertThrows(Exception.class, () -> subscriber.getBody().toCompletableFuture().get());
        assertTrue(cancelled.get());
    }

    @Test void boundedSubscriberAcceptsBodyAtLimit() throws Exception {
        var method = ModUpdater.class.getDeclaredMethod("boundedBody", long.class); method.setAccessible(true);
        @SuppressWarnings("unchecked") HttpResponse.BodyHandler<byte[]> handler =
                (HttpResponse.BodyHandler<byte[]>) method.invoke(null, 3L);
        HttpResponse.BodySubscriber<byte[]> subscriber = handler.apply(responseInfo());
        subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            public void request(long n) {}
            public void cancel() { fail("under-limit body must not cancel"); }
        });
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1}), ByteBuffer.wrap(new byte[] {2, 3})));
        subscriber.onComplete();
        assertArrayEquals(new byte[] {1, 2, 3}, subscriber.getBody().toCompletableFuture().get());
    }

    @Test void fabricJarRequiresIdentityAndMinecraftMetadata() throws Exception {
        Properties expected = expected("fabric");
        Path good = jar("good.jar", expected, "fabric.mod.json", "{\"id\":\"billding\",\"version\":\"0.1.2\",\"environment\":\"client\",\"depends\":{\"minecraft\":\"[1.21.4,1.22)\"}}");
        assertDoesNotThrow(() -> ModUpdater.validateJar(good, expected, "0.1.2"));

        Path bad = jar("bad.jar", expected, "fabric.mod.json", "{}");
        assertThrows(Exception.class, () -> ModUpdater.validateJar(bad, expected, "0.1.2"));
    }

    @Test void identityMismatchVersionLoaderMinecraftHashAndTraversalAreRejected() throws Exception {
        Properties expected = expected("fabric");
        Path wrongVersion = jarWithIdentity("wrong.jar", expected, "0.1.3");
        assertThrows(Exception.class, () -> ModUpdater.validateJar(wrongVersion, expected, "0.1.2"));
        Properties wrongLoader = expected("neoforge");
        Path wrong = jar("wrong-loader.jar", wrongLoader, "fabric.mod.json", "{}");
        assertThrows(Exception.class, () -> ModUpdater.validateJar(wrong, expected, "0.1.2"));
        assertThrows(Exception.class, () -> ExitInstaller.directFile(dir, "../evil.jar"));
        assertThrows(Exception.class, () -> ExitInstaller.directFile(dir, "x/y.jar"));
    }

    @Test void neoforgeJarChecksModsAndDependencyRange() throws Exception {
        Properties expected = expected("neoforge");
        String toml = "[[mods]]\nmodId=\"billding\"\nversion=\"0.1.2\"\n\n[[dependencies.billding]]\nmodId=\"minecraft\"\nversionRange=\"[1.21.4,1.22)\"\n";
        Path good = jar("neo.jar", expected, "META-INF/neoforge.mods.toml", toml);
        assertDoesNotThrow(() -> ModUpdater.validateJar(good, expected, "0.1.2"));
        String multiline = toml.replace("version=\"0.1.2\"", "version=\"0.1.2\"\ndescription='''\n[[mods]]\nmodId=\"billding\"\nversion=\"0.1.2\"\n'''");
        assertDoesNotThrow(() -> ModUpdater.validateJar(jar("neo-multiline.jar", expected, "META-INF/neoforge.mods.toml", multiline), expected, "0.1.2"));
        Path bad = jar("neo-bad.jar", expected, "META-INF/neoforge.mods.toml", toml.replace("1.21.4,1.22", "1.20.1,1.21"));
        assertThrows(Exception.class, () -> ModUpdater.validateJar(bad, expected, "0.1.2"));
        // 빌띵 NeoForge 메타는 정확 범위 "[1.21.4]" 를 쓴다 — 이게 거부되면 NeoForge 자동 업데이트가 전부 막힌다.
        Path exact = jar("neo-exact.jar", expected, "META-INF/neoforge.mods.toml", toml.replace("[1.21.4,1.22)", "[1.21.4]"));
        assertDoesNotThrow(() -> ModUpdater.validateJar(exact, expected, "0.1.2"));
        Path exactOther = jar("neo-exact-other.jar", expected, "META-INF/neoforge.mods.toml", toml.replace("[1.21.4,1.22)", "[1.21.3]"));
        assertThrows(Exception.class, () -> ModUpdater.validateJar(exactOther, expected, "0.1.2"));
        String fake = "[[mods]]\nmodId=\"other\"\nversion=\"0.1.2\"\ndescription=\"modId=\\\"billding\\\" version=\\\"0.1.2\\\"\"\n\n[[dependencies.billding]]\nmodId=\"minecraft\"\nversionRange=\"[1.21.4,1.22)\"\n";
        Path fakeDescription = jar("neo-description.jar", expected, "META-INF/neoforge.mods.toml", fake);
        assertThrows(Exception.class, () -> ModUpdater.validateJar(fakeDescription, expected, "0.1.2"));
    }

    @Test void actualLoaderMetadataTemplatesAreAcceptedAfterVersionExpansion() throws Exception {
        String fabric = Files.readString(resourcePath("fabric", "fabric.mod.json")).replace("${version}", "0.1.2");
        assertDoesNotThrow(() -> ModUpdater.validateJar(
                jar("actual-fabric.jar", expected("fabric"), "fabric.mod.json", fabric), expected("fabric"), "0.1.2"));
        String neo = Files.readString(resourcePath("neoforge", "META-INF/neoforge.mods.toml")).replace("${version}", "0.1.2");
        assertDoesNotThrow(() -> ModUpdater.validateJar(
                jar("actual-neo.jar", expected("neoforge"), "META-INF/neoforge.mods.toml", neo), expected("neoforge"), "0.1.2"));
    }

    private Properties expected(String loader) {
        Properties p = new Properties();
        p.setProperty("modId", "billding"); p.setProperty("loader", loader);
        p.setProperty("minecraft", "1.21.4"); p.setProperty("version", "0.1.2");
        p.setProperty("repository", "kpar03004-droid/billding");
        p.setProperty("prefix", "billding-" + loader + "-");
        return p;
    }

    private ModUpdater updater(ModUpdater.Fetcher fetcher, java.util.function.Consumer<String> messages) throws Exception {
        Properties identity = expected("fabric");
        identity.setProperty("version", "0.1.1");
        return new ModUpdater(identity, "billding", dir, dir.resolve("current.jar"), messages, fetcher);
    }

    private static void awaitIdle(ModUpdater updater) throws Exception {
        for (int i = 0; i < 200 && updater.isBusy(); i++) Thread.sleep(5);
        assertFalse(updater.isBusy(), "updater worker did not finish");
    }

    private static HttpResponse.ResponseInfo responseInfo() {
        return new HttpResponse.ResponseInfo() {
            public int statusCode() { return 200; }
            public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }

    private static Path resourcePath(String project, String relative) {
        for (Path base : List.of(Path.of(project), Path.of("..", project))) {
            Path path = base.resolve("src/main/resources").resolve(relative);
            if (Files.isRegularFile(path)) return path;
        }
        throw new AssertionError("Loader resource not found: " + project + "/" + relative);
    }

    private Path jar(String name, Properties expected, String entry, String body) throws Exception {
        Path file = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new JarEntry("META-INF/billding-update.properties"));
            expected.store(out, "identity"); out.closeEntry();
            out.putNextEntry(new JarEntry(entry)); out.write(body.getBytes(StandardCharsets.UTF_8)); out.closeEntry();
        }
        return file;
    }

    private Path jarWithIdentity(String name, Properties expected, String version) throws Exception {
        Properties actual = new Properties(); actual.putAll(expected); actual.setProperty("version", version);
        return jar(name, actual, "fabric.mod.json", "{}");
    }
}
