/**
 * Digi-Launcher - OSGi framework launcher for Equinox environment.
 *
 * Copyright (c) 2014 Alexey Aksenov ezh@ezh.msk.ru
 *
 * This file is licensed under the Apache License since
 * it is derived work of Twiter Eval.
 * Application is published under compatible LGPLv3.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.digimead.digi.launcher.osgi

import java.io.{ File, FileInputStream, InputStream }
import java.math.BigInteger
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.jar.JarFile
import scala.collection.mutable
import scala.language.reflectiveCalls
import scala.reflect.internal.util.{ BatchSourceFile, Position }
import scala.tools.nsc.interpreter.AbstractFileClassLoader
import scala.tools.nsc.io.VirtualDirectory
import scala.tools.nsc.reporters.AbstractReporter
import scala.tools.nsc.{ Global, Settings }

/**
 * Evaluates files, strings, or input streams as Scala code, and returns the result.
 *
 * Script also supports a limited set of preprocessors. Currently, "limited" means "exactly one":
 * directives of the form `#include <file>`.
 *
 * The flow of evaluation is:
 * - extract a string of code from the file, string, or input stream
 * - run preprocessors on that string
 * - wrap processed code in an `apply` method in a generated class
 * - compile the class
 * - contruct an instance of that class
 * - return the result of `apply()`
 */
class Script() {
  lazy val compilerPath = try classPathOfClass("scala.tools.nsc.Interpreter") catch {
    case e: Throwable ⇒
      throw new RuntimeException("Unable lo load scala interpreter from classpath (scala-compiler jar is missing?)", e)
  }
  lazy val libPath = try classPathOfClass("scala.Symbol") catch {
    case e: Throwable ⇒
      throw new RuntimeException("Unable to load scala base object from classpath (scala-library jar is missing?)", e)
  }

  /**
   * Preprocessors to run the code through before it is passed to the Scala compiler.
   * if you want to add new resolvers, you can do so with
   * new Eval(...) {
   *   lazy val preprocessors = {...}
   * }
   */
  protected lazy val preprocessors: Seq[Preprocessor] =
    Seq(
      new IncludePreprocessor(
        Seq(
          new ClassScopedResolver(getClass),
          new FilesystemResolver(new File(".")),
          new FilesystemResolver(new File("." + File.separator + "config"))) ++ (
            Option(System.getProperty("org.digimead.digi.launcher.osgi.includePath")) map { path ⇒
              new FilesystemResolver(new File(path))
            })))

  private[this] val STYLE_INDENT = 2
  private[this] lazy val compiler = new StringCompiler(STYLE_INDENT)

  /**
   * run preprocessors on our string, returning a String that is the processed source
   */
  def sourceForString(code: String): String = {
    preprocessors.foldLeft(code) { (acc, p) ⇒
      p(acc)
    }
  }

  /** val i: Int = new Eval()("1 + 1") // => 2 */
  def apply[T](code: String, resetState: Boolean = true): T = {
    val processed = sourceForString(code)
    applyProcessed(processed, resetState)
  }
  /** val i: Int = new Eval()(new File("...")) */
  def apply[T](file: File): T = {
    compiler.reset()
    apply(scala.io.Source.fromFile(file).mkString, true)
  }
  /**
   * same as apply[T], but does not run preprocessors.
   * Will generate a classname of the form Evaluater__<unique>,
   * where unique is computed from the jvmID (a random number)
   * and a digest of code
   */
  def applyProcessed[T](code: String, resetState: Boolean): T = {
    val id = uniqueId(code)
    val className = "Evaluator__" + id
    applyProcessed(className, code, resetState)
  }
  /** same as apply[T], but does not run preprocessors. */
  def applyProcessed[T](className: String, code: String, resetState: Boolean): T = {
    val cls = compiler(wrapCodeInClass(className, code), className, resetState)
    cls.getConstructor().newInstance().asInstanceOf[() ⇒ Any].apply().asInstanceOf[T]
  }
  /**
   * Compile an entire source file into the virtual classloader.
   */
  def compile(code: String) {
    compiler(sourceForString(code))
  }
  def findClass(className: String): Class[_] = {
    compiler.findClass(className).getOrElse { throw new ClassNotFoundException("no such class: " + className) }
  }

  private def uniqueId(code: String): String = {
    val digest = MessageDigest.getInstance("SHA-1").digest(code.getBytes())
    new BigInteger(1, digest).toString(16)
  }
  /*
   * Wrap source code in a new class with an apply method.
   */
  private def wrapCodeInClass(className: String, code: String) = {
    "class " + className + " extends (() => Any) {\n" +
      "  def apply() = {\n" +
      code + "\n" +
      "  }\n" +
      "}\n"
  }

  /*
   * For a given FQ classname, trick the resource finder into telling us the containing jar.
   */
  private def classPathOfClass(className: String) = {
    val resource = className.split('.').mkString("/", "/", ".class")
    val path = Option(getClass.getResource(resource)) match {
      case Some(resource) ⇒ resource.getPath
      case None ⇒ throw new IllegalStateException(s"Unable to find resource ${resource} with ${getClass}.")
    }
    if (path.indexOf("file:") >= 0) {
      val indexOfFile = path.indexOf("file:") + 5
      val indexOfSeparator = path.lastIndexOf('!')
      List(path.substring(indexOfFile, indexOfSeparator))
    } else {
      require(path.endsWith(resource))
      List(path.substring(0, path.length - resource.length + 1))
    }
  }

  /*
   * Try to guess our app's classpath.
   * This is probably fragile.
   */
  lazy val impliedClassPath: List[String] = {
    def getClassPath(cl: ClassLoader, acc: List[List[String]] = List.empty): List[List[String]] = {
      val cp = cl match {
        case urlClassLoader: URLClassLoader ⇒ urlClassLoader.getURLs.filter(_.getProtocol == "file").
          map(u ⇒ new File(u.toURI).getPath).toList
        case _ ⇒ Nil
      }
      cl.getParent match {
        case null ⇒ (cp :: acc).reverse
        case parent ⇒ getClassPath(parent, cp :: acc)
      }
    }

    val classPath = getClassPath(this.getClass.getClassLoader)
    val currentClassPath = classPath.head

    // if there's just one thing in the classpath, and it's a jar, assume an executable jar.
    currentClassPath ::: (if (currentClassPath.size == 1 && currentClassPath(0).endsWith(".jar")) {
      val jarFile = currentClassPath(0)
      val relativeRoot = new File(jarFile).getParentFile()
      val nestedClassPath = new JarFile(jarFile).getManifest.getMainAttributes.getValue("Class-Path")
      if (nestedClassPath eq null) {
        Nil
      } else {
        nestedClassPath.split(" ").map { f ⇒ new File(relativeRoot, f).getAbsolutePath }.toList
      }
    } else {
      Nil
    }) ::: classPath.tail.flatten
  }

  trait Preprocessor {
    def apply(code: String): String
  }

  trait Resolver {
    def resolvable(path: String): Boolean
    def get(path: String): InputStream
  }

  class FilesystemResolver(root: File) extends Resolver {
    private[this] def file(path: String): File =
      new File(root.getAbsolutePath + File.separator + path)

    def resolvable(path: String): Boolean =
      file(path).exists

    def get(path: String): InputStream =
      new FileInputStream(file(path))
  }

  class ClassScopedResolver(clazz: Class[_]) extends Resolver {
    private[this] def quotePath(path: String) =
      "/" + path

    def resolvable(path: String): Boolean =
      clazz.getResourceAsStream(quotePath(path)) != null

    def get(path: String): InputStream =
      clazz.getResourceAsStream(quotePath(path))
  }

  class ResolutionFailedException(message: String) extends Exception

  /*
   * This is a preprocesor that can include files by requesting them from the given classloader
   *
   * Thusly, if you put FS directories on your classpath (e.g. config/ under your app root,) you
   * mix in configuration from the filesystem.
   *
   * @example #include file-name.scala
   *
   * This is the only directive supported by this preprocessor.
   *
   * Note that it is *not* recursive. Included files cannot have includes
   */
  class IncludePreprocessor(resolvers: Seq[Resolver]) extends Preprocessor {
    def maximumRecursionDepth = 100

    def apply(code: String): String =
      apply(code, maximumRecursionDepth)

    def apply(code: String, maxDepth: Int): String = {
      val lines = code.lines map { line: String ⇒
        val tokens = line.trim.split(' ')
        if (tokens.length == 2 && tokens(0).equals("#include")) {
          val path = tokens(1)
          resolvers find { resolver: Resolver ⇒
            resolver.resolvable(path)
          } match {
            case Some(r: Resolver) ⇒ {
              // recursively process includes
              if (maxDepth == 0) {
                throw new IllegalStateException("Exceeded maximum recusion depth")
              } else {
                //StreamIO.buffer(r.get(path)).toString
                // TODO !!!
                apply("", maxDepth - 1)
              }
            }
            case _ ⇒
              throw new IllegalStateException("No resolver could find '%s'".format(path))
          }
        } else {
          line
        }
      }
      lines.mkString("\n")
    }
  }

  /**
   * Dynamic scala compiler. Lots of (slow) state is created, so it may be advantageous to keep
   * around one of these and reuse it.
   */
  private class StringCompiler(lineOffset: Int) {
    val target = new VirtualDirectory("(memory)", None)
    val cache = new mutable.HashMap[String, Class[_]]()

    val settings = new Settings
    settings.nowarnings.value = true // warnings are exceptions, so disable
    settings.outputDirs.setSingleOutput(target)

    val pathList = compilerPath ::: libPath
    settings.bootclasspath.value = pathList.mkString(File.pathSeparator)
    settings.classpath.value = (pathList ::: impliedClassPath).mkString(File.pathSeparator)

    val reporter = new AbstractReporter {
      val settings = StringCompiler.this.settings
      val messages = new mutable.ListBuffer[List[String]]

      def display(pos: Position, message: String, severity: Severity) {
        severity.count += 1
        val severityName = severity match {
          case ERROR ⇒ "error: "
          case WARNING ⇒ "warning: "
          case _ ⇒ ""
        }
        // the line number is not always available
        val lineMessage =
          try {
            "line " + (pos.line - lineOffset)
          } catch {
            case _: Throwable ⇒ ""
          }
        messages += (severityName + lineMessage + ": " + message) ::
          (if (pos.isDefined) {
            pos.inUltimateSource(pos.source).lineContent.stripLineEnd ::
              (" " * (pos.column - 1) + "^") ::
              Nil
          } else {
            Nil
          })
      }

      def displayPrompt {
        // no.
      }

      override def reset {
        super.reset
        messages.clear()
      }
    }

    val global = new Global(settings, reporter)

    /*
     * Class loader for finding classes compiled by this StringCompiler.
     * After each reset, this class loader will not be able to find old compiled classes.
     */
    var classLoader = new AbstractFileClassLoader(target, this.getClass.getClassLoader)

    def reset() {
      target.asInstanceOf[VirtualDirectory].clear
      cache.clear()
      reporter.reset
      classLoader = new AbstractFileClassLoader(target, this.getClass.getClassLoader)
    }

    object Debug {
      val enabled =
        System.getProperty("eval.debug") != null

      def printWithLineNumbers(code: String) {
        printf("Code follows (%d bytes)\n", code.length)

        var numLines = 0
        code.lines foreach { line: String ⇒
          numLines += 1
          println(numLines.toString.padTo(5, ' ') + "| " + line)
        }
      }
    }

    def findClass(className: String): Option[Class[_]] = {
      synchronized {
        cache.get(className).orElse {
          try {
            val cls = classLoader.loadClass(className)
            cache(className) = cls
            Some(cls)
          } catch {
            case e: ClassNotFoundException ⇒ None
          }
        }
      }
    }

    /**
     * Compile scala code. It can be found using the above class loader.
     */
    def apply(code: String) {
      if (Debug.enabled)
        Debug.printWithLineNumbers(code)

      // if you're looking for the performance hit, it's 1/2 this line...
      val compiler = new global.Run
      val sourceFiles = List(new BatchSourceFile("(inline)", code))
      // ...and 1/2 this line:
      compiler.compileSources(sourceFiles)

      if (reporter.hasErrors || reporter.WARNING.count > 0) {
        throw new CompilerException(reporter.messages.toList)
      }
    }

    /**
     * Compile a new class, load it, and return it. Thread-safe.
     */
    def apply(code: String, className: String, resetState: Boolean = true): Class[_] = {
      synchronized {
        if (resetState) reset()
        findClass(className).getOrElse {
          apply(code)
          findClass(className).get
        }
      }
    }
  }

  class CompilerException(val messages: List[List[String]]) extends Exception(
    "Compiler exception " + messages.map(_.mkString("\n")).mkString("\n"))
}
