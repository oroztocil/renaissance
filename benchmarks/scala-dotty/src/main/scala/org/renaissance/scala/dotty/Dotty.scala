package org.renaissance.scala.dotty

import org.renaissance.Benchmark
import org.renaissance.Benchmark._
import org.renaissance.BenchmarkContext
import org.renaissance.BenchmarkResult
import org.renaissance.BenchmarkResult.Validators
import org.renaissance.License

import java.io._
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import scala.collection._

// Keep for cross-compilation with Scala 2.11 and 2.12.
import scala.collection.compat._

@Name("dotty")
@Group("scala-dotty")
@Summary("Runs the Dotty compiler on a set of source code files.")
@Licenses(Array(License.BSD3))
@Repetitions(50)
// Work around @Repeatable annotations not working in this Scala version.
@Configurations(Array(new Configuration(name = "test"), new Configuration(name = "jmh")))
final class Dotty extends Benchmark {

  // TODO: Consolidate benchmark parameters across the suite.
  //  See: https://github.com/renaissance-benchmarks/renaissance/issues/27

  private val zipPath = "sources.zip"

  private val dottyPath = Paths.get("target", "dotty")

  private val sourceCodePath = dottyPath.resolve("src")

  private val outputPath = dottyPath.resolve("output")

  private var dottyBaseArgs: Seq[String] = _

  private var dottyInvocations: Seq[Array[String]] = _

  private val batchCompile = false

  private def unzipSources() = {
    val sources = mutable.Buffer[Path]()

    val zis = new ZipInputStream(this.getClass.getResourceAsStream("/" + zipPath))
    try {
      LazyList.continually(zis.getNextEntry).takeWhile(_ != null).foreach { zipEntry =>
        if (!zipEntry.isDirectory) {
          val target = sourceCodePath.resolve(zipEntry.getName)
          val parent = target.getParent
          if (parent != null && Files.notExists(parent)) {
            Files.createDirectories(parent)
          }

          Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING)
          sources += target
        }
      }
    } finally {
      zis.close()
    }

    sources.toSeq
  }

  override def setUpBeforeAll(c: BenchmarkContext): Unit = {
    /*
     * Construct the classpath for the compiler. Unfortunately, Dotty is
     * unable to use current classloader (either of this class or this
     * thread) and thus we have to explicitly pass it. Note that
     * -usejavacp would not work here as that reads from java.class.path
     * property and we do not want to modify global properties here.
     *
     * Therefore, we leverage the fact that we know that our classloader
     * is actually a URLClassLoader that loads the benchmark JARs
     * from temporary directory. And we convert all the URLs to
     * plain file paths.
     *
     * Note that using URLs as-is is not possible as that prepends the
     * "file:/" protocol that is not handled well on Windows when
     * on classpath.
     *
     * Note that it would be best to pass the classloader to the compiler
     * but that seems to be impossible with current API (see discussion
     * at https://github.com/renaissance-benchmarks/renaissance/issues/176).
     */
    val classPath = Thread.currentThread.getContextClassLoader
      .asInstanceOf[URLClassLoader]
      .getURLs
      .map(url => new java.io.File(url.toURI).getPath)
      .mkString(File.pathSeparator)

    dottyBaseArgs = Seq[String](
      "-classpath",
      classPath,
      // Allow the compiler to automatically perform implicit type conversions.
      "-language:implicitConversions",
      // Output directory for compiled classes.
      "-d",
      outputPath.toString
    )

    Files.createDirectories(outputPath)
    val sourcePaths = unzipSources()

    if (batchCompile) {
      val dottyArgs = (dottyBaseArgs ++ sourcePaths.map(_.toString)).toArray
      dottyInvocations = Seq(dottyArgs)
    } else {
      // Compile sources one-by-one.
      dottyInvocations = sourcePaths.map(p => (dottyBaseArgs :+ p.toString).toArray)
    }
  }

  override def run(c: BenchmarkContext): BenchmarkResult = {
    dottyInvocations.foreach { dottyArgs =>
      dotty.tools.dotc.Main.process(dottyArgs)
    }

    // TODO: add proper validation
    Validators.dummy()
  }
}
