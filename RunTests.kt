import java.io.File
import java.util.jar.JarFile

/** Reflection runner: executes every @org.junit.Test method in the given jar's classes. */
fun main(args: Array<String>) {
    val jar = File(args[0])
    val filter = args.getOrNull(1)
    val loader = java.net.URLClassLoader(arrayOf(jar.toURI().toURL()), Thread.currentThread().contextClassLoader)
    var passed = 0; var failed = 0
    JarFile(jar).use { jf ->
        for (entry in jf.entries().asSequence().filter { it.name.endsWith("Test.class") || it.name.endsWith("Tests.class") }) {
            val cls = loader.loadClass(entry.name.removeSuffix(".class").replace('/', '.'))
            if (filter != null && !cls.simpleName.contains(filter)) continue
            val tests = cls.methods.filter { m -> m.isAnnotationPresent(org.junit.Test::class.java) }
            if (tests.isEmpty()) continue
            val instance = try { cls.getDeclaredConstructor().newInstance() } catch (e: java.lang.reflect.InvocationTargetException) {
                failed += tests.size; println("FAIL ${cls.simpleName}: constructor failed: ${e.targetException}"); continue
            }
            for (m in tests.sortedBy { it.name }) {
                val t0 = System.currentTimeMillis()
                val expected = m.getAnnotation(org.junit.Test::class.java).expected.java
                val expectsThrow = expected != org.junit.Test.None::class.java
                try {
                    m.invoke(instance)
                    if (expectsThrow) { failed++; println("FAIL ${cls.simpleName}.${m.name}: expected ${expected.name}") }
                    else { passed++; println("PASS ${cls.simpleName}.${m.name} (${System.currentTimeMillis() - t0} ms)") }
                }
                catch (e: java.lang.reflect.InvocationTargetException) {
                    if (expectsThrow && expected.isInstance(e.targetException)) { passed++; println("PASS ${cls.simpleName}.${m.name} (expected exception)"); continue }
                    failed++; println("FAIL ${cls.simpleName}.${m.name}: ${e.targetException}")
                    e.targetException.stackTrace.take(4).forEach { println("     at $it") }
                }
            }
        }
    }
    println("TOTAL passed=$passed failed=$failed")
    if (failed > 0) System.exit(1)
}
