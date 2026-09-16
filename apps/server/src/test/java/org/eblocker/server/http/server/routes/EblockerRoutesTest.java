package org.eblocker.server.http.server.routes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import org.eblocker.server.http.server.StaticFileController;
import org.junit.jupiter.api.Test;
import org.restexpress.RestExpress;
import org.restexpress.route.parameterized.ParameterizedRouteBuilder;
import org.restexpress.route.regex.RegexRouteBuilder;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Characterizes all legacy route declarations without opening a listening socket. */
class EblockerRoutesTest {
    @Test
    void preservesEveryRouteInOrderIncludingAuthorizationAndSerialization() throws Exception {
        List<Map<String, Object>> expected;
        try (InputStream input = getClass().getResourceAsStream("/contracts/http-routes.json")) {
            assertNotNull(input);
            expected = new ObjectMapper().readValue(input,
                    new TypeReference<List<Map<String, Object>>>() { });
        }

        List<Map<String, Object>> additions;
        try (InputStream input = getClass().getResourceAsStream("/contracts/http-routes-additions.json")) {
            assertNotNull(input);
            additions = new ObjectMapper().readValue(input,
                    new TypeReference<List<Map<String, Object>>>() { });
        }

        Map<Object, String> controllerNames = new IdentityHashMap<>();
        Set<Class<?>> controllerTypes = new LinkedHashSet<>();
        for (Class<?> feature : new Class<?>[]{AuthenticationRoutes.class, DevicesRoutes.class,
                DiagnosticsRoutes.class, ExperienceRoutes.class, FamilyRoutes.class,
                NetworkRoutes.class, ProtectionRoutes.class, SystemRoutes.class, VpnRoutes.class}) {
            for (Constructor<?> constructor : feature.getDeclaredConstructors()) {
                for (Class<?> type : constructor.getParameterTypes()) {
                    controllerTypes.add(type);
                }
            }
        }
        controllerTypes.add(StaticFileController.class);
        EblockerRoutes routes = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                for (Class<?> type : controllerTypes) {
                    bindController(type, controllerNames);
                }
            }

            private <T> void bindController(Class<T> type, Map<Object, String> names) {
                T controller = mock(type);
                names.put(controller, type.getSimpleName());
                bind(type).toInstance(controller);
            }
        }).getInstance(EblockerRoutes.class);

        List<Map<String, Object>> actual = new ArrayList<>();
        RestExpress server = mock(RestExpress.class);
        when(server.uri(anyString(), any())).thenAnswer(invocation ->
                record(ParameterizedRouteBuilder.class, "uri", invocation.getArgument(0),
                        controllerNames.get(invocation.getArgument(1)), actual));
        when(server.regex(anyString(), any())).thenAnswer(invocation ->
                record(RegexRouteBuilder.class, "regex", invocation.getArgument(0),
                        controllerNames.get(invocation.getArgument(1)), actual));

        routes.register(server);

        assertEquals(expected, actual.stream().filter(route -> !additions.contains(route)).toList(),
                "Original route order, actions, names, flags and serialization are API contracts");
        assertEquals(additions, actual.stream().filter(additions::contains).toList(),
                "Every added route must match its explicit authorization contract exactly once");
        assertEquals("/.*", actual.get(actual.size() - 1).get("path"));
    }

    private <T> T record(Class<T> builderType, String kind, String path, String controller,
                         List<Map<String, Object>> actual) {
        assertNotNull(controller, "Every route must use an injected controller");
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("kind", kind);
        route.put("path", path);
        route.put("controller", controller);
        List<List<String>> steps = new ArrayList<>();
        route.put("steps", steps);
        actual.add(route);
        return mock(builderType, invocation -> {
            List<String> step = new ArrayList<>();
            step.add(invocation.getMethod().getName());
            for (Object argument : invocation.getArguments()) {
                step.add(argument.toString());
            }
            steps.add(step);
            return invocation.getMock();
        });
    }
}
