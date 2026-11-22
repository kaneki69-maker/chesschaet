package org.junit;
import java.lang.annotation.*;
/** Minimal JUnit4 stand-in so unit tests can run on a plain JVM without Gradle/Maven. */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
public @interface Test {
    Class<? extends Throwable> expected() default None.class;
    class None extends Throwable { private None() {} }
}
