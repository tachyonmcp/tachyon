package dev.tachyonmcp.docs.kotlin

import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit.SECONDS

internal const val DOCUMENTED_PORT = 8080

internal class ForkedMain private constructor(
    private val process: Process,
) : AutoCloseable {
    override fun close() {
        process.destroy()
        if (!process.waitFor(10, SECONDS)) {
            process.destroyForcibly().waitFor()
        }
    }

    companion object {
        fun start(mainClass: String): ForkedMain {
            assumeTrue(portIsFree(), "port $DOCUMENTED_PORT is in use; the page's examples bind it")
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val classpath =
                System.getProperty("surefire.test.class.path") ?: System.getProperty(
                    @Suppress("ktlint:standard:max-line-length")
                    "java.class.path",
                )
            val process =
                ProcessBuilder(java, "-cp", classpath, mainClass)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
            val forked = ForkedMain(process)
            try {
                await()
                    .atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofMillis(200))
                    .until {
                        check(process.isAlive) { "$mainClass exited with ${process.exitValue()}" }
                        listening()
                    }
            } catch (e: Throwable) {
                forked.close()
                throw e
            }
            return forked
        }

        private fun portIsFree(): Boolean =
            runCatching {
                ServerSocket().use {
                    it.bind(
                        InetSocketAddress("127.0.0.1", DOCUMENTED_PORT),
                    )
                }
            }.isSuccess

        private fun listening(): Boolean =
            runCatching {
                Socket().use {
                    it.connect(
                        InetSocketAddress("127.0.0.1", DOCUMENTED_PORT),
                        200,
                    )
                }
            }.isSuccess
    }
}
