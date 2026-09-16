// SPDX-License-Identifier: EUPL-1.2
package org.eblocker.server.http.server;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.restexpress.Request;
import org.restexpress.Response;
import org.restexpress.exception.NotFoundException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NextConsoleServingTest {
    @TempDir Path directory;
    private StaticFileController controller;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(directory.resolve("next/assets"));
        Files.createDirectories(directory.resolve("settings"));
        Files.writeString(directory.resolve("next/index.html"), "<html>New console</html>");
        Files.writeString(directory.resolve("next/assets/app.js"), "export const ready = true;");
        Files.writeString(directory.resolve("settings/index.html"), "<html>Existing settings</html>");
        controller = new StaticFileController(directory.toString(), 3600, "eblocker.box", "https");
    }

    @Test
    void servesConsoleWithStrictPolicyAndRevalidatesIndex() throws Exception {
        Response response = new Response();
        assertEquals("<html>New console</html>", read("/next/", response));
        assertEquals("text/html; charset=UTF-8", response.getContentType());
        assertEquals("private,max-age=0,must-revalidate", response.getHeader("Cache-Control"));
        assertTrue(response.getHeader("Content-Security-Policy").contains("frame-ancestors 'none'"));
        assertFalse(response.getHeader("Content-Security-Policy").contains("unsafe-inline"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        Response conditional = new Response();
        Request request = request("/next/");
        request.addHeader("If-Modified-Since", response.getHeader("Last-Modified"));
        assertNull(controller.read(request, conditional));
        assertEquals(HttpResponseStatus.NOT_MODIFIED, conditional.getResponseStatus());
        assertEquals(response.getHeader("Content-Security-Policy"), conditional.getHeader("Content-Security-Policy"));
        assertEquals(response.getHeader("Cache-Control"), conditional.getHeader("Cache-Control"));
    }

    @Test
    void servesHashedJavaScriptAndPreservesExistingSettingsPolicy() throws Exception {
        Response assets = new Response();
        assertEquals("export const ready = true;", read("/next/assets/app.js", assets));
        assertTrue(assets.getContentType().contains("javascript"));
        assertEquals("private,max-age=3600", assets.getHeader("Cache-Control"));
        Response legacy = new Response();
        assertEquals("<html>Existing settings</html>", read("/settings/", legacy));
        assertNull(legacy.getHeader("Content-Security-Policy"));
        assertEquals("private,max-age=3600", legacy.getHeader("Cache-Control"));
    }

    @Test
    void canonicalizesDirectoryAndDoesNotInventHistoryFallback() throws Exception {
        Response response = new Response();
        assertNull(controller.read(request("/next"), response));
        assertEquals(HttpResponseStatus.MOVED_PERMANENTLY, response.getResponseStatus());
        assertEquals("/next/", response.getHeader("location"));
        assertThrows(NotFoundException.class, () -> controller.read(request("/next/devices/missing"), new Response()));
    }

    private String read(String path, Response response) throws Exception {
        ByteBuf body = (ByteBuf) controller.read(request(path), response);
        try { return body.toString(StandardCharsets.UTF_8); } finally { body.release(); }
    }

    private Request request(String path) {
        return new Request(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path), null);
    }
}
