package stirling.software.jpdfium.samples;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/** Runs every sample as a parameterized integration test. */
@EnabledIfSystemProperty(named = "jpdfium.integration", matches = "true")
class SamplesSuiteTest {

    private static final String[] EMPTY_ARGS = new String[0];

    @BeforeAll
    static void ensureNative() {
        SampleBase.ensureNative();
    }

    static Stream<Arguments> sampleClasses() {
        return Stream.of(
                Arguments.of("S01_Document", S01_Document.class),
                Arguments.of("S02_Render", S02_Render.class),
                Arguments.of("S03_Text", S03_Text.class),
                Arguments.of("S04_Images", S04_Images.class),
                Arguments.of("S05_Redact", S05_Redact.class),
                Arguments.of("S06_Forms", S06_Forms.class),
                Arguments.of("S07_MergeSplit", S07_MergeSplit.class),
                Arguments.of("S08_Compress", S08_Compress.class),
                Arguments.of("S09_Security", S09_Security.class),
                Arguments.of("S10_Pipeline", S10_Pipeline.class)
        );
    }

    @AfterEach
    void releaseMemory() {
        System.gc();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sampleClasses")
    void sampleRunsWithoutException(String name, Class<?> sampleClass) throws Exception {
        Method main = sampleClass.getMethod("main", String[].class);
        assertDoesNotThrow(() -> {
            try {
                main.invoke(null, (Object) EMPTY_ARGS);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }, name + " threw an exception");
    }
}
