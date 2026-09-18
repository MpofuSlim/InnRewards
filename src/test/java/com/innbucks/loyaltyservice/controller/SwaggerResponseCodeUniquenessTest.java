package com.innbucks.loyaltyservice.controller;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No controller method may declare the same {@code responseCode} twice.
 *
 * <p>OpenAPI keys a method's responses BY STATUS, so two
 * {@code @ApiResponse(responseCode = "400", …)} entries on one handler are not
 * merged and do not both render — one silently wins and the other's
 * description and examples vanish from the published spec. Nothing complains:
 * it compiles, it boots, Swagger UI renders a page that simply omits half of
 * what the author wrote.
 *
 * <p>This is not hypothetical. {@code VoucherController.redeem} carried two
 * {@code "400"} blocks — one for bean validation, one for EXPIRED — and had
 * done for months; the EXPIRED documentation was never reaching anyone. It was
 * found by reading the file, which is not a method that scales.
 *
 * <p>The fix when this fails is to MERGE the blocks: one {@code @ApiResponse}
 * per status with several named {@code @ExampleObject}s, which is the shape the
 * rest of this service already uses.
 */
class SwaggerResponseCodeUniquenessTest {

    /**
     * Every {@code @RestController} in the service, found by walking the
     * compiled classes rather than by a hand-maintained list — a new controller
     * is covered the day it is written, which is the only way a convention test
     * stays true.
     */
    private static List<Class<?>> controllers() {
        Path root;
        try {
            root = Path.of(SwaggerResponseCodeUniquenessTest.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Could not locate the compiled test classes", e);
        }
        // test-classes -> classes: the controllers live beside us in the build.
        Path classes = root.resolveSibling("classes");
        assertThat(Files.isDirectory(classes))
                .as("expected compiled main classes at %s", classes)
                .isTrue();

        List<Class<?>> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classes)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> classes.relativize(p).toString()
                            .replace(java.io.File.separatorChar, '.')
                            .replaceAll("\\.class$", ""))
                    .sorted()
                    .forEach(name -> {
                        try {
                            Class<?> c = Class.forName(name, false,
                                    SwaggerResponseCodeUniquenessTest.class.getClassLoader());
                            if (c.isAnnotationPresent(RestController.class)) found.add(c);
                        } catch (Throwable ignored) {
                            // A class that cannot be loaded without initialising
                            // Spring is not a controller we can inspect; skip it
                            // rather than failing the whole convention check.
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    @Test
    void noHandlerDeclaresTheSameResponseCodeTwice() {
        List<Class<?>> controllers = controllers();
        assertThat(controllers)
                .as("the class walk found no @RestControllers — the test would pass vacuously")
                .isNotEmpty();

        List<String> offenders = new ArrayList<>();
        for (Class<?> controller : controllers) {
            for (Method m : controller.getDeclaredMethods()) {
                ApiResponses responses = m.getAnnotation(ApiResponses.class);
                if (responses == null) continue;

                Set<String> seen = new HashSet<>();
                for (ApiResponse r : responses.value()) {
                    if (!seen.add(r.responseCode())) {
                        offenders.add(controller.getSimpleName() + "." + m.getName()
                                + " declares responseCode \"" + r.responseCode() + "\" more than once");
                    }
                }
            }
        }

        offenders.sort(Comparator.naturalOrder());
        assertThat(offenders)
                .as("OpenAPI keys responses by status, so a duplicate silently drops one block "
                        + "from the published spec — merge them into one @ApiResponse with several "
                        + "named @ExampleObjects instead")
                .isEmpty();
    }
}
