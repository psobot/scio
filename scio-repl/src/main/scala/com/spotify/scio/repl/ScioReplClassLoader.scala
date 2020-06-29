/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.repl

import java.io.{File, FileOutputStream}
import java.net.{URL, URLClassLoader}
import java.nio.file.Files
import java.util.jar.{JarEntry, JarOutputStream}
import java.lang.invoke.{MethodHandles, MethodType}

import com.spotify.scio.repl.compat.ILoopClassLoader
import org.slf4j.LoggerFactory

import scala.tools.nsc.io._
import scala.collection.AbstractIterable

object ScioReplClassLoader {
  private val Logger = LoggerFactory.getLogger(this.getClass)

  private[this] val JDK9OrHigher: Boolean = util.Properties.isJavaAtLeast("9")

  private[this] val BootClassLoader: ClassLoader = {
    if (!JDK9OrHigher) null
    else {
      try {
        MethodHandles
          .lookup()
          .findStatic(
            classOf[ClassLoader],
            "getPlatformClassLoader",
            MethodType.methodType(classOf[ClassLoader])
          )
          .invoke()
      } catch { case _: Throwable => null }
    }
  }

  def classLoaderURLs(cl: ClassLoader): Array[java.net.URL] = cl match {
    case null                       => Array.empty
    case u: java.net.URLClassLoader => u.getURLs ++ classLoaderURLs(cl.getParent)
    case _                          => classLoaderURLs(cl.getParent)
  }

  @inline final def apply(urls: Array[URL]) =
    new ScioReplClassLoader(urls, BootClassLoader)
}

/**
 * Class loader with option to lookup classes in REPL classloader.
 * Some help/code from Twitter Scalding.
 * @param urls classpath urls for URLClassLoader
 * @param parent parent for Scio CL - may be null to close the chain
 */
class ScioReplClassLoader(urls: Array[URL], parent: ClassLoader)
    extends URLClassLoader(urls, parent)
    with ILoopClassLoader {
  import ScioReplClassLoader.Logger

  private val replJarName = "scio-repl-session.jar"
  private var nextReplJarDir: File = genNextReplCodeJarDir

  override def loadClass(name: String): Class[_] =
    // If contains $line - means that repl was loaded, so we can lookup
    // runtime classes
    if (name.contains("$line")) {
      Logger.debug(s"Trying to load $name")
      // Don't want to use Try{} cause nonFatal handling
      val clazz: Class[_] =
        try {
          scioREPL.classLoader.loadClass(name)
        } catch {
          case e: Exception =>
            Logger.error(s"Could not find $name in REPL classloader", e)
            null
        }
      if (clazz != null) {
        Logger.debug(s"Found $name in REPL classloader ${scioREPL.classLoader}")
        clazz
      } else {
        super.loadClass(name)
      }
    } else {
      super.loadClass(name)
    }

  def genNextReplCodeJarDir: File =
    Files.createTempDirectory("scio-repl-").toFile
  def getNextReplCodeJarPath: String =
    new File(nextReplJarDir, replJarName).getAbsolutePath

  /**
   * Creates a jar file in a temporary directory containing the code thus far compiled by the REPL.
   *
   * @return some file for the jar created, or `None` if the REPL is not running
   */
  private[scio] def createReplCodeJar: String = {
    require(scioREPL != null, "scioREPL can't be null - set it first!")
    val tempJar = new File(nextReplJarDir, replJarName)
    // Generate next repl jar dir
    nextReplJarDir = genNextReplCodeJarDir
    createJar(scioREPL.outputDir, tempJar).getPath()
  }

  /**
   * Creates a jar file from the classes contained in a virtual directory.
   *
   * @param virtualDirectory containing classes that should be added to the jar
   */
  private def createJar(dir: Iterable[AbstractFile], jarFile: File): File = {
    val jarStream = new JarOutputStream(new FileOutputStream(jarFile))
    try { addVirtualDirectoryToJar(dir, "", jarStream) }
    finally { jarStream.close() }

    jarFile
  }

  /**
   * Add the contents of the specified virtual directory to a jar. This method will recursively
   * descend into subdirectories to add their contents.
   *
   * @param dir is a virtual directory whose contents should be added
   * @param entryPath for classes found in the virtual directory
   * @param jarStream for writing the jar file
   */
  private def addVirtualDirectoryToJar(
    dir: Iterable[AbstractFile],
    entryPath: String,
    jarStream: JarOutputStream
  ): Unit = dir.foreach { file =>
    if (file.isDirectory) {
      // Recursively descend into subdirectories, adjusting the package name as we do.
      val dirPath = entryPath + file.name + "/"
      jarStream.putNextEntry(new JarEntry(dirPath))
      jarStream.closeEntry()
      addVirtualDirectoryToJar(file, dirPath, jarStream)
    } else if (file.hasExtension("class")) {
      // Add class files as an entry in the jar file and write the class to the jar.
      jarStream.putNextEntry(new JarEntry(entryPath + file.name))
      jarStream.write(file.toByteArray)
      jarStream.closeEntry()
    }
  }
}
